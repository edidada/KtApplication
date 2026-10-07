package com.example.ktapplication.vehicle.domain

import com.example.ktapplication.vehicle.domain.model.ChargingStatus
import com.example.ktapplication.vehicle.domain.model.ClimateStatus
import com.example.ktapplication.vehicle.domain.model.DataFreshness
import com.example.ktapplication.vehicle.domain.model.DoorPosition
import com.example.ktapplication.vehicle.domain.model.DoorState
import com.example.ktapplication.vehicle.domain.model.GearPosition
import com.example.ktapplication.vehicle.domain.model.LockState
import com.example.ktapplication.vehicle.domain.model.SeatbeltState
import com.example.ktapplication.vehicle.domain.model.SignalHealth
import com.example.ktapplication.vehicle.domain.model.Telemetry
import com.example.ktapplication.vehicle.domain.model.ValueSource
import com.example.ktapplication.vehicle.domain.model.VehicleStatus

/**
 * 领域对象测试夹具。
 *
 * VehicleStatus 有十几个字段，每个测试里手搭一遍会让断言被构造代码淹没；
 * 集中一个带默认值的工厂，测试只覆盖自己关心的那几个字段。
 */
internal fun telemetryOf(value: Double?, source: ValueSource = ValueSource.CAN_BUS, at: Long = 100_000L): Telemetry<Double> =
    Telemetry(value, source, at, if (value == null) DataFreshness.UNAVAILABLE else DataFreshness.FRESH)

internal fun vehicleStatusFixture(
    speed: Double = 0.0,
    soc: Double = 60.0,
    range: Double = 293.0,
    gear: GearPosition = GearPosition.PARK,
    lock: LockState = LockState.LOCKED,
    seatbelt: SeatbeltState = SeatbeltState.BUCKLED,
    targetTemp: Double = 24.0,
    compressorOn: Boolean = false,
    pluggedIn: Boolean = false,
    chargeCurrent: Double = 0.0,
    chargePower: Double = 0.0,
    packVoltage: Double = 399.0,
    doors: Map<DoorPosition, Telemetry<DoorState>> = mapOf(
        DoorPosition.FRONT_LEFT to Telemetry(DoorState.CLOSED, ValueSource.CAN_BUS, 100_000L),
        DoorPosition.FRONT_RIGHT to Telemetry(DoorState.CLOSED, ValueSource.CAN_BUS, 100_000L),
    ),
    updatedAt: Long = 100_000L,
    busState: String = "STREAMING",
    frameRateHz: Double = 50.0,
    source: ValueSource = ValueSource.CAN_BUS,
): VehicleStatus = VehicleStatus(
    vehicleId = "LVSHCDAAXFA000123",
    speedKilometresPerHour = telemetryOf(speed, source, updatedAt),
    stateOfChargePercent = telemetryOf(soc, source, updatedAt),
    remainingRangeKilometres = telemetryOf(range, source, updatedAt),
    odometerKilometres = telemetryOf(23_456.7, source, updatedAt),
    gear = Telemetry(gear, source, updatedAt),
    lockState = Telemetry(lock, source, updatedAt),
    doors = doors,
    driverSeatbelt = Telemetry(seatbelt, source, updatedAt),
    climate = ClimateStatus(
        driverTargetCelsius = telemetryOf(targetTemp, source, updatedAt),
        passengerTargetCelsius = telemetryOf(targetTemp, source, updatedAt),
        fanLevel = Telemetry(3, source, updatedAt),
        compressorOn = compressorOn,
        recirculation = false,
    ),
    charging = ChargingStatus(
        pluggedIn = Telemetry(pluggedIn, source, updatedAt),
        powerKilowatts = telemetryOf(chargePower, source, updatedAt),
        currentAmperes = telemetryOf(chargeCurrent, source, updatedAt),
        packVoltageVolts = telemetryOf(packVoltage, source, updatedAt),
        chargePortLocked = !pluggedIn,
    ),
    lowBatteryVolts = telemetryOf(12.4, source, updatedAt),
    updatedAtMillis = updatedAt,
    signalHealth = SignalHealth(
        canBusState = busState,
        carServiceState = "Ready",
        cloudState = "ONLINE",
        staleSignals = emptyList(),
        frameRateHz = frameRateHz,
        busDropRatesPercent = emptyMap(),
    ),
)
