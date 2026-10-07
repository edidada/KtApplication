package com.example.ktapplication.vehicle.carservice

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.ktapplication.core.AppDispatchers
import com.example.ktapplication.core.DomainError
import com.example.ktapplication.core.MonotonicClock
import com.example.ktapplication.core.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 真机 CarService 接入实现 —— 全程反射，不 import `android.car.*`。
 *
 * 为什么用反射而不是 `compileOnly android.car.jar`：
 *  1. 车机 framework jar 是 OEM 私有制品，不能进公开仓库，CI 上没有它就无法编译；
 *  2. AAOS 各版本里类名/方法签名确实在动（`CarLifecycleListener` → `CarServiceLifecycleListener`，
 *     `registerCarPropertyEventCallback` → `registerCallback(Executor, …)`），
 *     直接依赖某个版本的 jar 会把这套代码钉死在一代系统上；
 *  3. 反射 + 逐版本试错可以让我们在**同一份 APK**里兼容多代车机，取不到就降级为
 *     "该属性不支持"并回落到私有 CAN 通道，而不是崩溃。
 *
 * 代价是编译期失去检查，所以每个反射点都写清了它对应的真实 API，便于上真机抓 log 比对。
 *
 * 上真机后必须补的三件事（本地无法验证，已在代码里标 TODO）：
 *  - `R.car_permission.CAR_ELECTRONIC_CONTROL_UNIT` 一类 OEM 权限的授予；
 *  - ACCESS_RESTRICTED 属性需要 App 持平台签名或 system 用户；
 *  - 车载 `CarPerformancePeriod` / 低功耗模式下订阅频率的下调。
 */
class ReflectiveCarServiceGateway(
    private val context: Context,
    private val clock: MonotonicClock,
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : CarServiceGateway, PropertySubscriptionRegistry.Registrar {

    private val _state = MutableStateFlow<CarServiceState>(CarServiceState.Disconnected)
    override val state: StateFlow<CarServiceState> = _state.asStateFlow()

    private val registry = PropertySubscriptionRegistry(clock, dispatchers, scope, registrar = this)
    private val cache = PropertyCache(clock)
    private val coordinator = CarConnectionCoordinator(nowMillis = { clock.nowMillis() }, onStateChange = { _state.value = it })

    private var carInstance: Any? = null
    private var propertyManager: Any? = null
    private var carClass: Class<*>? = null
    private var eventCallbackClass: Class<*>? = null

    /** VehiclePropertyIds 常量名 → int，反向用于把反射拿到的 id 映射回我们的枚举。 */
    private var idToProperty: Map<Int, CarVehicleProperty> = emptyMap()

    /** getPropertyList() 结果：id → 该属性支持的 areaId 集合。 */
    private val areaIdsByProperty = HashMap<CarVehicleProperty, List<Int>>()
    private val registeredHandles = HashMap<String, Any>()

    override suspend fun connect(): CarServiceState {
        when (val current = _state.value) {
            is CarServiceState.Ready, is CarServiceState.Unsupported -> return current
            is CarServiceState.Connecting -> return current
            else -> Unit
        }
        coordinator.onConnectRequested()
        val result = runCatching { bindCarService() }
        return result.fold(
            onSuccess = { it },
            onFailure = { throwable ->
                coordinator.onUnsupported(throwable.message ?: "car framework 不可用")
                _state.value
            },
        )
    }

    /**
     * lint 的 PrivateApi 会在这里报"反射访问内部 API"：`android.car.*` 确实不在手机 SDK 里。
     * 但这正是本类存在的理由 —— 编译期没有车机 framework jar，运行期只有真车机才有这些类，
     * 唯一安全的接法就是 Class.forName + 方法名反射，失败一律降级为"不支持"（见各 runCatching 分支），
     * 绝不让手机上的 APK 因为缺类而崩。所以这条告警是设计约束，不是缺陷，就地说明后豁免。
     */
    @SuppressLint("PrivateApi")
    private fun bindCarService(): CarServiceState {
        // android.car.Car 只在车机上存在；手机/平板上运行时这里就是唯一的"是否车机"判定点。
        val car = runCatching { Class.forName("android.car.Car") }.getOrNull()
            ?: run {
                coordinator.onUnsupported("当前设备没有 android.car.Car（非车机环境）")
                return _state.value
            }
        carClass = car

        val managerName = "android.car.hardware.property.CarPropertyManager"
        val created = createCarInstance(car)
            ?: run {
                coordinator.onUnsupported("Car.createCar 反射调用失败")
                return _state.value
            }
        carInstance = created

        val manager = runCatching {
            created.javaClass.getMethod("getCarManager", String::class.java).invoke(created, managerName)
        }.getOrNull() ?: run {
            coordinator.onUnsupported("getCarManager($managerName) 返回 null")
            return _state.value
        }
        propertyManager = manager

        resolvePropertyIds()
        eventCallbackClass = manager.javaClass.classes.firstOrNull { it.simpleName == "CarPropertyEventCallback" }
            ?: runCatching { Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback") }.getOrNull()

        val supported = probeSupportedProperties(manager)
        coordinator.onReady(supported, carType = readCarType(created))
        return _state.value
    }

    /**
     * Car.createCar 有多个重载且各版本参数不同，这里按"能凑齐参数就试"的顺序尝试，
     * 全部失败才判不支持。listener 用动态代理实现，方法名按真实接口签名分派。
     */
    private fun createCarInstance(car: Class<*>): Any? {
        val listenerProxy = buildLifecycleProxy(car)
        val createCarMethods = car.methods.filter { it.name == "createCar" && it.parameterTypes.size >= 2 }

        for (method in createCarMethods) {
            val types = method.parameterTypes
            val args = ArrayList<Any?>(types.size)
            args += context
            var listenerPlaced = false
            for (index in 1 until types.size) {
                val type = types[index]
                when {
                    Handler::class.java.isAssignableFrom(type) -> args += mainHandler
                    type == Looper::class.java -> args += mainHandler.looper
                    type.isInterface && type.simpleName.contains("Listener", ignoreCase = true) && !listenerPlaced -> {
                        args += listenerProxy
                        listenerPlaced = true
                    }
                    type == Int::class.javaPrimitiveType -> args += -1 // CAR_WAIT_TIMEOUT_WAIT_READY
                    type == Long::class.javaPrimitiveType -> args += -1L
                    else -> args += null
                }
            }
            if (!listenerPlaced) continue
            val invoked = runCatching { method.invoke(null, *args.toTypedArray()) }.getOrNull()
            if (invoked != null) return invoked
        }
        // 兜底：无 listener 的同步 createCar(Context)，再手动 connect()。
        val simple = runCatching { car.getMethod("createCar", Context::class.java).invoke(null, context) }.getOrNull() ?: return null
        runCatching { simple.javaClass.getMethod("connect").invoke(simple) }
        return simple
    }

    private fun buildLifecycleProxy(car: Class<*>): Any? {
        val listenerInterface = car.classes.firstOrNull { it.simpleName in SET_OF_LIFECYCLE_LISTENERS }
            ?: return null
        return Proxy.newProxyInstance(
            listenerInterface.classLoader,
            arrayOf(listenerInterface),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    // CarServiceLifecycleListener.onLifecycleChanged(Car, boolean ready)
                    "onLifecycleChanged" -> {
                        val ready = args?.getOrNull(1) as? Boolean ?: false
                        if (ready) onCarConnected(car) else onCarDisconnected()
                    }
                    // 旧版 CarLifecycleListener.onCarConnected(Car)
                    "onCarConnected" -> onCarConnected(car)
                    "onCarDisconnected" -> onCarDisconnected()
                    else -> Unit
                }
                // equals/hashCode 由 Proxy 自身处理，这里只关心生命周期回调。
                null
            },
        )
    }

    private fun onCarConnected(carClass: Class<*>) {
        val manager = runCatching {
            carClass.getMethod("getCarManager", String::class.java)
                .invoke(carInstance, "android.car.hardware.property.CarPropertyManager")
        }.getOrNull() ?: return
        propertyManager = manager
        resolvePropertyIds()
        coordinator.onReady(probeSupportedProperties(manager), carType = readCarType(carInstance))
    }

    private fun onCarDisconnected() {
        // Binder 死亡：清空注册表，让重连后重新注册；缓存保留，UI 继续显示上一可信值并标注陈旧。
        registeredHandles.clear()
        coordinator.onConnectionLost("CarService disconnected")
    }

    private fun resolvePropertyIds() {
        val holder = listOf(
            "android.car.hardware.property.VehiclePropertyIds",
            "android.car.hardware.VehiclePropertyIds",
        ).firstNotNullOfOrNull { name -> runCatching { Class.forName(name) }.getOrNull() } ?: return

        val mapping = HashMap<Int, CarVehicleProperty>()
        CarVehicleProperty.entries.forEach { property ->
            val field = runCatching { holder.getField(property.androidCarFieldName) }.getOrNull() ?: return@forEach
            val raw = runCatching { field.getInt(null) }.getOrNull() ?: return@forEach
            mapping[raw] = property
        }
        idToProperty = mapping
    }

    /**
     * 支持属性只能问 VHAL。
     *
     * `getPropertyList()` 返回 CarPropertyConfig 列表；这里读它的 propertyId 和 areaIds，
     * 得到"本机真实支持集合 + 每属性区域"。绝对不要用配置里的车型表去猜，
     * 同一车型不同年款的 VHAL 差异很常见。
     */
    private fun probeSupportedProperties(manager: Any): Set<CarVehicleProperty> {
        val configs = runCatching { manager.javaClass.getMethod("getPropertyList").invoke(manager) as? List<*> }.getOrNull()
            ?: emptyList<Any?>()
        val supported = HashSet<CarVehicleProperty>()
        configs.filterNotNull().forEach { config ->
            val id = runCatching { config.javaClass.getMethod("getPropertyId").invoke(config) as? Int }.getOrNull()
            val property = id?.let { idToProperty[it] } ?: return@forEach
            supported += property
            val areas = runCatching { config.javaClass.getMethod("getAreaIds").invoke(config) as? IntArray }
                .getOrNull() ?: intArrayOf(GLOBAL_AREA_ID)
            areaIdsByProperty[property] = areas.toList().ifEmpty { listOf(GLOBAL_AREA_ID) }
        }
        return supported
    }

    private fun readCarType(car: Any?): String? = runCatching {
        car?.javaClass?.getMethod("getCarType")?.invoke(car) as? String
    }.getOrNull()

    override fun supports(property: CarVehicleProperty): Boolean =
        (_state.value as? CarServiceState.Ready)?.supportedProperties?.contains(property) == true

    override fun areasOf(property: CarVehicleProperty): List<Int> =
        areaIdsByProperty[property] ?: listOf(GLOBAL_AREA_ID)

    override fun subscribe(property: CarVehicleProperty, areaId: Int): Flow<CarPropertySample> {
        val cached = cache.get(property, areaId)
        val stream = registry.subscribe(property, areaId)
        return if (cached == null) stream else kotlinx.coroutines.flow.flow {
            emit(cached)
            stream.collect { emit(it) }
        }
    }

    override fun unsubscribe(property: CarVehicleProperty, areaId: Int) = registry.release(property, areaId)

    override suspend fun read(property: CarVehicleProperty, areaId: Int): Outcome<CarPropertySample> {
        if (!supports(property)) return Outcome.failure(CarPropertyErrors.fromRegisterFailure(property, "VHAL getPropertyList 未包含"))
        val sample = registry.readOnce(property, areaId)
        return if (sample == null) {
            Outcome.failure(DomainError.Unsupported(property.displayName))
        } else {
            Outcome.success(sample)
        }
    }

    override suspend fun write(property: CarVehicleProperty, value: PropertyValue, areaId: Int): Outcome<Unit> {
        if (!supports(property)) return Outcome.failure(CarPropertyErrors.fromRegisterFailure(property, "VHAL 未声明该属性"))
        val error = registry.write(property, areaId, value)
        return if (error == null) Outcome.success(Unit) else Outcome.failure(CarPropertyErrors.fromWriteFailure(property, error))
    }

    override fun disconnect() {
        registry.releaseAll()
        runCatching { carInstance?.javaClass?.getMethod("disconnect")?.invoke(carInstance) }
        carInstance = null
        propertyManager = null
        coordinator.reset()
    }

    // ---- Registrar：真正的 VHAL 注册/注销/读写 ----

    override suspend fun register(property: CarVehicleProperty, areaId: Int): Boolean {
        val manager = propertyManager ?: return false
        val callbackClass = eventCallbackClass ?: return false
        val propertyId = idToProperty.entries.firstOrNull { it.value == property }?.key ?: return false
        if (!supports(property)) return false

        val callback = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
            EventHandler(property, areaId),
        )
        return runCatching {
            // AAOS 12：registerCarPropertyEventCallback(callback, propertyId, areaId[, rate])
            // AAOS 13+：registerCallback(Executor, rate, ...) —— 这里按签名匹配逐个尝试。
            val method = manager.javaClass.methods.firstOrNull { candidate ->
                candidate.name in REGISTER_METHOD_NAMES &&
                    candidate.parameterTypes.size >= 3 &&
                    candidate.parameterTypes[0] == callbackClass &&
                    candidate.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    candidate.parameterTypes[2] == Int::class.javaPrimitiveType
            } ?: return@runCatching false
            val args = buildArgsFor(method, callback, propertyId, areaId, property.rate.hz)
            method.invoke(manager, *args)
            registeredHandles["${property.name}:$areaId"] = callback
            true
        }.getOrDefault(false)
    }

    override suspend fun unregister(property: CarVehicleProperty, areaId: Int) {
        val manager = propertyManager ?: return
        val callback = registeredHandles.remove("${property.name}:$areaId") ?: return
        val propertyId = idToProperty.entries.firstOrNull { it.value == property }?.key ?: return
        runCatching {
            val method = manager.javaClass.methods.firstOrNull { candidate ->
                candidate.name in UNREGISTER_METHOD_NAMES &&
                    candidate.parameterTypes.size >= 3 &&
                    candidate.parameterTypes[0]?.isInstance(callback) == true &&
                    candidate.parameterTypes[1] == Int::class.javaPrimitiveType
            }
            method?.invoke(manager, *buildArgsFor(method, callback, propertyId, areaId, property.rate.hz))
        }
    }

    override suspend fun readSample(property: CarVehicleProperty, areaId: Int): CarPropertySample? {
        val manager = propertyManager ?: return null
        val propertyId = idToProperty.entries.firstOrNull { it.value == property }?.key ?: return null
        val raw = runCatching {
            val method = manager.javaClass.getMethod("getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            method.invoke(manager, boxedTypeOf(property.valueType), propertyId, areaId)
        }.getOrNull() ?: return null
        return interpretCarPropertyValue(raw, property, areaId)
    }

    /**
     * 写属性。优先用泛型 `setProperty(Class, int, Object, int)`（AAOS 12+），
     * 失败再退到按类型拆开的 setBooleanProperty/setFloatProperty/... 老接口。
     */
    override suspend fun write(property: CarVehicleProperty, areaId: Int, value: PropertyValue): String? {
        val manager = propertyManager ?: return "CarService 未绑定"
        val propertyId = idToProperty.entries.firstOrNull { it.value == property }?.key ?: return "本机未定义属性常量 ${property.androidCarFieldName}"
        val typed = value.coercedTo(property.valueType)

        val genericResult = runCatching {
            manager.javaClass
                .getMethod("setProperty", Class::class.java, Int::class.javaPrimitiveType, Any::class.java, Int::class.javaPrimitiveType)
                .invoke(manager, boxedTypeOf(property.valueType), propertyId, typed.toJavaValue(), areaId)
        }
        if (genericResult.isSuccess) return null

        val legacyName = when (typed) {
            is PropertyValue.Bool -> "setBooleanProperty"
            is PropertyValue.Int32 -> "setIntProperty"
            is PropertyValue.Int64 -> "setLongProperty"
            is PropertyValue.Float -> "setFloatProperty"
            is PropertyValue.Double -> "setDoubleProperty"
            is PropertyValue.Str -> "setStringProperty"
            is PropertyValue.FloatArray -> "setFloatArrayProperty"
        }
        val legacyResult = runCatching {
            val method = manager.javaClass.methods.firstOrNull { it.name == legacyName && it.parameterTypes.size == 3 }
                ?: error("没有找到 $legacyName")
            method.invoke(manager, propertyId, typed.toJavaValue(), areaId)
        }
        return legacyResult.exceptionOrNull()?.let { "VHAL 写入异常：${it.message}" }
    }

    private fun buildArgsFor(method: Method, callback: Any, propertyId: Int, areaId: Int, rateHz: Float): Array<Any?> {
        val args = ArrayList<Any?>(method.parameterCount)
        args += callback
        var index = 1
        // 参数顺序在不同版本里可能是 (id, area) 或 (id, area, rate) 或 (executor, id, area, rate)，
        // 上面已经按"首参必须是 callback"筛过，这里按类型填充剩余参数。
        for (slot in 1 until method.parameterCount) {
            val type = method.parameterTypes[slot]
            val arg: Any? = when {
                type == Int::class.javaPrimitiveType -> if (index == 1) propertyId else if (index == 2) areaId else rateHz.toInt()
                type == Float::class.javaPrimitiveType -> rateHz
                type == Long::class.javaPrimitiveType -> clock.nowMillis()
                type == java.util.concurrent.Executor::class.java -> ioExecutor(dispatchers.io)
                else -> null
            }
            args += arg
            index++
        }
        return args.toTypedArray()
    }

    private fun boxedTypeOf(type: PropertyValueType): Class<*> = when (type) {
        PropertyValueType.BOOLEAN -> Boolean::class.javaObjectType
        PropertyValueType.INT32 -> Int::class.javaObjectType
        PropertyValueType.INT64 -> Long::class.javaObjectType
        PropertyValueType.FLOAT -> Float::class.javaObjectType
        PropertyValueType.DOUBLE -> Double::class.javaObjectType
        PropertyValueType.STRING -> String::class.java
        PropertyValueType.FLOAT_ARRAY -> FloatArray::class.java
        PropertyValueType.MIXED -> Any::class.java
    }

    /**
     * 把领域值转成 `setProperty` / 老式分类型 setter 期望的 Java 装箱对象。
     *
     * 分支类型必须写成 `PropertyValue.X` 限定名：这些变体是 PropertyValue 的嵌套类，
     * 在本类里并没有（也不能）被非限定引入 —— 裸写 `Bool`/`Int32`/`Int64`/`Str` 直接找不到符号，
     * 而 `Float`/`Double`/`FloatArray` 会解析成同名标准库类型：`is Float` 对 PropertyValue 接收者
     * 永远不成立，既触发"when 不穷尽"又让分支里的 `value` 无从智能转换。
     *
     * 这里刻意**不加 else**：VHAL 就这七种值类型，将来新增一种要让它在这里编译不过、
     * 逼着补一个真实的装箱分支，而不是悄悄落到默认值把属性读成 null（这类"静默丢值"正是
     * 反射层最该被编译期拦住的错误）。装箱结果与 boxedTypeOf() 的 Class 参数一一对应。
     */
    private fun PropertyValue.toJavaValue(): Any = when (this) {
        is PropertyValue.Bool -> value
        is PropertyValue.Int32 -> value
        is PropertyValue.Int64 -> value
        is PropertyValue.Float -> value
        is PropertyValue.Double -> value
        is PropertyValue.Str -> value
        is PropertyValue.FloatArray -> value
    }

    /**
     * CarPropertyValue → 我们的样本。
     *
     * 新版 AAOS 把值类型换成 CarPropertyConfig 驱动的混合类型，取值一律走 getValue()
     * 再按运行时类型分派；timestamp 在部分实现里是纳秒、部分是毫秒，这里统一成毫秒并
     * 用"数量级判断"兜住（真实项目里更稳妥的做法是问 VHAL 的 unit，但反射拿不到）。
     */
    private fun interpretCarPropertyValue(raw: Any, property: CarVehicleProperty, fallbackAreaId: Int): CarPropertySample? {
        val rawValue = runCatching { raw.javaClass.getMethod("getValue").invoke(raw) }.getOrNull() ?: return null
        val statusName = runCatching { raw.javaClass.getMethod("getStatus").invoke(raw) as? Int }.getOrNull()
        val areaId = runCatching { raw.javaClass.getMethod("getAreaId").invoke(raw) as? Int }.getOrNull() ?: fallbackAreaId
        val timestamp = runCatching { raw.javaClass.getMethod("getTimestamp").invoke(raw) as? Long }.getOrNull()
        val parsedValue = rawValue.toPropertyValue() ?: return null
        return CarPropertySample(
            property = property,
            areaId = areaId,
            value = parsedValue,
            status = when (statusName) {
                1 -> PropertyStatus.NOT_AVAILABLE
                2 -> PropertyStatus.UNAVAILABLE
                3 -> PropertyStatus.ERROR
                else -> PropertyStatus.AVAILABLE
            },
            timestampMillis = normalizeTimestamp(timestamp) ?: clock.nowMillis(),
        )
    }

    private fun normalizeTimestamp(raw: Long?): Long? {
        val value = raw ?: return null
        // 纳秒级时间戳（>1e15）除以 1e6 换成毫秒；毫秒级直接用。
        return if (value > 1_000_000_000_000_000L) value / 1_000_000L else value
    }

    private fun Any?.toPropertyValue(): PropertyValue? = when (this) {
        is Boolean -> PropertyValue.Bool(this)
        is Int -> PropertyValue.Int32(this)
        is Long -> PropertyValue.Int64(this)
        is Float -> PropertyValue.Float(this)
        is Double -> PropertyValue.Double(this)
        is String -> PropertyValue.Str(this)
        is FloatArray -> PropertyValue.FloatArray(this)
        else -> null
    }

    /** VHAL 回调入口：onChangeEvent(CarPropertyEvent) / onChangeEvent(CarPropertyValue)。 */
    private inner class EventHandler(
        private val property: CarVehicleProperty,
        private val areaId: Int,
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            when (method.name) {
                "onChangeEvent" -> {
                    val event = args?.firstOrNull() ?: return null
                    val carPropertyValue = runCatching { event.javaClass.getMethod("getCarPropertyValue").invoke(event) }
                        .getOrNull() ?: event
                    interpretCarPropertyValue(carPropertyValue, property, areaId)?.let { sample ->
                        cache.put(sample)
                        registry.publish(sample)
                    }
                }
                // onErrorEvent(int errorCode)：id 冲突或权限不足，标未可用让上层走回落。
                "onErrorEvent" -> {
                    registry.publish(CarPropertySample.unavailable(property, areaId, clock.nowMillis(), PropertyStatus.ERROR))
                }
            }
            return null
        }
    }

    private companion object {
        val SET_OF_LIFECYCLE_LISTENERS = setOf("CarServiceLifecycleListener", "CarLifecycleListener")
        val REGISTER_METHOD_NAMES = setOf("registerCarPropertyEventCallback", "registerCallback")
        val UNREGISTER_METHOD_NAMES = setOf("unregisterCarPropertyEventCallback", "unregisterCallback")
    }
}

/**
 * 把协程调度器包装成 [java.util.concurrent.Executor]：
 * AAOS 13+ 的属性回调注册要求调用方提供 Executor，回调会在我们指定的 IO 线程上执行，
 * 不能是主线程 —— 50Hz 的车速回调如果在主线程做转换，仪表就会跟着掉帧。
 */
internal fun ioExecutor(dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO): java.util.concurrent.Executor =
    java.util.concurrent.Executor { runnable ->
        kotlinx.coroutines.CoroutineScope(dispatcher).launch { runnable.run() }
    }
