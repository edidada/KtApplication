package com.example.ktapplication.vehicle.can

/**
 * 信号表（等价于一份精简 DBC）。
 *
 * 注意：这里的 CAN ID、位域和换算系数是**为了跑通示例而按行业常见布局编的**，
 * 不是奇瑞量产车的真实信号定义。真机接入时这张表由整车厂发布的 .dbc 文件生成，
 * 座舱 App 不应该把位域常量硬编码在 Kotlin 里，正式做法是：
 *   dbc2kotlin / 云端下发信号表 → 编译期生成 [CanSignalCatalog]。
 */
object CanSignalCatalog {

    val SPEED_KMH = CanSignalDefinition(
        name = "VEH_SPEED",
        bus = CanBus.CHASSIS,
        canId = 0x0A0,
        startBit = 7,
        bitLength = 16,
        byteOrder = ByteOrder.MOTOROLA,
        factor = 0.01,
        unit = " km/h",
        minValue = 0.0,
        maxValue = 240.0,
        expectedPeriodMillis = 20,
        description = "整车车速，ABS 帧，16bit 跨字节大端",
    )

    val STEERING_ANGLE_DEG = CanSignalDefinition(
        name = "STR_ANGLE",
        bus = CanBus.CHASSIS,
        canId = 0x0A0,
        startBit = 23,
        bitLength = 16,
        byteOrder = ByteOrder.MOTOROLA,
        signed = true,
        factor = 0.1,
        unit = "°",
        minValue = -500.0,
        maxValue = 500.0,
        expectedPeriodMillis = 20,
        description = "方向盘转角，左正右负，与车速同一帧",
    )

    val WHEEL_SPEED_FL_KMH = CanSignalDefinition(
        name = "WHEEL_SPD_FL",
        bus = CanBus.CHASSIS,
        canId = 0x0B4,
        startBit = 0,
        bitLength = 13,
        byteOrder = ByteOrder.INTEL,
        factor = 0.05,
        unit = " km/h",
        minValue = 0.0,
        maxValue = 300.0,
        expectedPeriodMillis = 20,
        description = "左前轮速，13bit 非字节对齐，专门用来验证跨字节拆位",
    )

    val PACK_SOC_PERCENT = CanSignalDefinition(
        name = "BMS_SOC",
        bus = CanBus.POWERTRAIN,
        canId = 0x2C0,
        startBit = 7,
        bitLength = 8,
        byteOrder = ByteOrder.MOTOROLA,
        factor = 0.4,
        unit = "%",
        invalidRawPattern = 0xFF,
        expectedPeriodMillis = 100,
        description = "动力电池 SOC，0xFF 表示无效（下电初期常见）",
    )

    val PACK_VOLTAGE_V = CanSignalDefinition(
        name = "BMS_VOLTAGE",
        bus = CanBus.POWERTRAIN,
        canId = 0x2C0,
        startBit = 15,
        bitLength = 16,
        byteOrder = ByteOrder.MOTOROLA,
        factor = 0.05,
        unit = " V",
        expectedPeriodMillis = 100,
        description = "电池包总压",
    )

    val PACK_CURRENT_A = CanSignalDefinition(
        name = "BMS_CURRENT",
        bus = CanBus.POWERTRAIN,
        canId = 0x2C4,
        startBit = 7,
        bitLength = 16,
        byteOrder = ByteOrder.MOTOROLA,
        signed = true,
        factor = 0.1,
        offset = -2000.0,
        unit = " A",
        expectedPeriodMillis = 100,
        description = "电池电流，充电为正/放电为负，带 offset 的有符号信号",
    )

    val MOTOR_RPM = CanSignalDefinition(
        name = "MOTOR_RPM",
        bus = CanBus.POWERTRAIN,
        canId = 0x2C4,
        startBit = 23,
        bitLength = 16,
        byteOrder = ByteOrder.MOTOROLA,
        factor = 0.25,
        unit = " rpm",
        expectedPeriodMillis = 20,
        description = "驱动电机转速",
    )

    val GEAR_POSITION = CanSignalDefinition(
        name = "GEAR_SEL",
        bus = CanBus.POWERTRAIN,
        canId = 0x150,
        startBit = 3,
        bitLength = 3,
        byteOrder = ByteOrder.INTEL,
        invalidRawPattern = 0b111,
        expectedPeriodMillis = 50,
        description = "档位：0=P 1=R 2=N 3=D 7=无效",
    )

    val EV_RANGE_KM = CanSignalDefinition(
        name = "DC_RANGE",
        bus = CanBus.POWERTRAIN,
        canId = 0x2D0,
        startBit = 7,
        bitLength = 12,
        byteOrder = ByteOrder.MOTOROLA,
        factor = 0.5,
        unit = " km",
        invalidRawPattern = 0xFFF,
        expectedPeriodMillis = 1000,
        description = "剩余续航，12bit 起始位 7 → 跨 byte0/byte1",
    )

    val ODOMETER_KM = CanSignalDefinition(
        name = "ODO",
        bus = CanBus.INFOTAINMENT,
        canId = 0x3A0,
        startBit = 7,
        bitLength = 32,
        byteOrder = ByteOrder.MOTOROLA,
        factor = 0.1,
        unit = " km",
        expectedPeriodMillis = 1000,
        description = "总里程，32bit 跨 4 字节",
    )

    val DOOR_AJAR_FL = CanSignalDefinition(
        name = "DOOR_FL",
        bus = CanBus.BODY,
        canId = 0x0F0,
        startBit = 0,
        bitLength = 1,
        byteOrder = ByteOrder.INTEL,
        description = "左前门：0=关 1=开（事件型，无固定周期）",
    )

    val DOOR_AJAR_FR = CanSignalDefinition(
        name = "DOOR_FR",
        bus = CanBus.BODY,
        canId = 0x0F0,
        startBit = 1,
        bitLength = 1,
        byteOrder = ByteOrder.INTEL,
    )

    val DOOR_AJAR_RL = CanSignalDefinition(
        name = "DOOR_RL",
        bus = CanBus.BODY,
        canId = 0x0F0,
        startBit = 2,
        bitLength = 1,
        byteOrder = ByteOrder.INTEL,
    )

    val DOOR_AJAR_RR = CanSignalDefinition(
        name = "DOOR_RR",
        bus = CanBus.BODY,
        canId = 0x0F0,
        startBit = 3,
        bitLength = 1,
        byteOrder = ByteOrder.INTEL,
    )

    val LOCK_STATE = CanSignalDefinition(
        name = "CENTRAL_LOCK",
        bus = CanBus.BODY,
        canId = 0x0F0,
        startBit = 4,
        bitLength = 2,
        byteOrder = ByteOrder.INTEL,
        invalidRawPattern = 0b11,
        description = "中控锁：0=解锁 1=上锁 2=部分上锁 3=无效",
    )

    val SEATBELT_BUCKLED_DRIVER = CanSignalDefinition(
        name = "SEATBELT_DRV",
        bus = CanBus.BODY,
        canId = 0x0F0,
        startBit = 15,
        bitLength = 2,
        byteOrder = ByteOrder.INTEL,
        description = "主驾安全带：0=未系 1=已系 2=未安装",
    )

    val HVAC_TEMP_DRIVER_C = CanSignalDefinition(
        name = "HVAC_T_DRV",
        bus = CanBus.BODY,
        canId = 0x1E0,
        startBit = 7,
        bitLength = 8,
        byteOrder = ByteOrder.MOTOROLA,
        // 温度信号业界惯例是**无符号 + 负 offset**（-40℃ 偏移），不是补码：
        // 用补码时 8bit 只能到 23.5℃，32℃ 会被解成 -96℃ 的假故障。
        signed = false,
        factor = 0.5,
        offset = -40.0,
        unit = " ℃",
        minValue = 14.0,
        maxValue = 32.0,
        expectedPeriodMillis = 200,
        description = "主驾出风目标温度，补码 + offset，用来验证 -40 边界",
    )

    val HVAC_TEMP_PASSENGER_C = CanSignalDefinition(
        name = "HVAC_T_PSG",
        bus = CanBus.BODY,
        canId = 0x1E0,
        startBit = 15,
        bitLength = 8,
        byteOrder = ByteOrder.MOTOROLA,
        signed = false,
        factor = 0.5,
        offset = -40.0,
        unit = " ℃",
        expectedPeriodMillis = 200,
    )

    val HVAC_FAN_LEVEL = CanSignalDefinition(
        name = "HVAC_FAN",
        bus = CanBus.BODY,
        canId = 0x1E0,
        startBit = 16,
        bitLength = 3,
        byteOrder = ByteOrder.INTEL,
        expectedPeriodMillis = 200,
        description = "风量 0..7",
    )

    val CHARGE_PLUGGED = CanSignalDefinition(
        name = "CHG_PLUG",
        bus = CanBus.POWERTRAIN,
        canId = 0x2E0,
        startBit = 0,
        bitLength = 1,
        byteOrder = ByteOrder.INTEL,
        expectedPeriodMillis = 500,
        description = "充电枪连接状态",
    )

    val CHARGE_POWER_KW = CanSignalDefinition(
        name = "CHG_POWER",
        bus = CanBus.POWERTRAIN,
        canId = 0x2E0,
        startBit = 7,
        bitLength = 8,
        byteOrder = ByteOrder.MOTOROLA,
        factor = 0.5,
        unit = " kW",
        expectedPeriodMillis = 500,
    )

    val TYRE_PRESSURE_FL_KPA = CanSignalDefinition(
        name = "TPMS_FL",
        bus = CanBus.CHASSIS,
        canId = 0x330,
        startBit = 7,
        bitLength = 8,
        byteOrder = ByteOrder.MOTOROLA,
        factor = 4.0,
        unit = " kPa",
        invalidRawPattern = 0xFF,
        expectedPeriodMillis = 1000,
    )

    val LOW_BATTERY_POWER_C = CanSignalDefinition(
        name = "12V_VOLT",
        bus = CanBus.BODY,
        canId = 0x340,
        startBit = 7,
        bitLength = 8,
        byteOrder = ByteOrder.MOTOROLA,
        factor = 0.05,
        unit = " V",
        expectedPeriodMillis = 1000,
        description = "小电瓶电压，车机黑屏/重启类问题第一步就看它",
    )

    val all: List<CanSignalDefinition> = listOf(
        SPEED_KMH, STEERING_ANGLE_DEG, WHEEL_SPEED_FL_KMH,
        PACK_SOC_PERCENT, PACK_VOLTAGE_V, PACK_CURRENT_A, MOTOR_RPM, GEAR_POSITION,
        EV_RANGE_KM, ODOMETER_KM,
        DOOR_AJAR_FL, DOOR_AJAR_FR, DOOR_AJAR_RL, DOOR_AJAR_RR, LOCK_STATE,
        SEATBELT_BUCKLED_DRIVER,
        HVAC_TEMP_DRIVER_C, HVAC_TEMP_PASSENGER_C, HVAC_FAN_LEVEL,
        CHARGE_PLUGGED, CHARGE_POWER_KW,
        TYRE_PRESSURE_FL_KPA, LOW_BATTERY_POWER_C,
    )

    private val byNameIndex: Map<String, CanSignalDefinition> =
        all.associateBy { it.name }

    /** (bus, canId) → 该帧内的信号列表，收帧后 O(1) 命中，避免每帧遍历整张表。 */
    private val byFrameIndex: Map<Pair<CanBus, Int>, List<CanSignalDefinition>> =
        all.groupBy { it.bus to it.canId }

    fun definition(name: String): CanSignalDefinition? = byNameIndex[name]

    fun definitionsFor(frame: CanFrame): List<CanSignalDefinition> =
        byFrameIndex[frame.bus to frame.id].orEmpty()

    val buses: Set<CanBus> = all.map { it.bus }.toSet()
}
