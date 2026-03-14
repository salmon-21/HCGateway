package dev.shuchir.hcgateway.domain.model

import androidx.health.connect.client.records.*

data class RecordTypeInfo(
    val name: String,
    val recordClass: kotlin.reflect.KClass<out Record>,
)

val RECORD_TYPES = listOf(
    RecordTypeInfo("ActiveCaloriesBurned", ActiveCaloriesBurnedRecord::class),
    RecordTypeInfo("BasalBodyTemperature", BasalBodyTemperatureRecord::class),
    RecordTypeInfo("BasalMetabolicRate", BasalMetabolicRateRecord::class),
    RecordTypeInfo("BloodGlucose", BloodGlucoseRecord::class),
    RecordTypeInfo("BloodPressure", BloodPressureRecord::class),
    RecordTypeInfo("BodyFat", BodyFatRecord::class),
    RecordTypeInfo("BodyTemperature", BodyTemperatureRecord::class),
    RecordTypeInfo("BoneMass", BoneMassRecord::class),
    RecordTypeInfo("CervicalMucus", CervicalMucusRecord::class),
    RecordTypeInfo("CyclingPedalingCadence", CyclingPedalingCadenceRecord::class),
    RecordTypeInfo("Distance", DistanceRecord::class),
    RecordTypeInfo("ElevationGained", ElevationGainedRecord::class),
    RecordTypeInfo("ExerciseSession", ExerciseSessionRecord::class),
    RecordTypeInfo("FloorsClimbed", FloorsClimbedRecord::class),
    RecordTypeInfo("HeartRate", HeartRateRecord::class),
    RecordTypeInfo("Height", HeightRecord::class),
    RecordTypeInfo("Hydration", HydrationRecord::class),
    RecordTypeInfo("IntermenstrualBleeding", IntermenstrualBleedingRecord::class),
    RecordTypeInfo("LeanBodyMass", LeanBodyMassRecord::class),
    RecordTypeInfo("MenstruationFlow", MenstruationFlowRecord::class),
    RecordTypeInfo("MenstruationPeriod", MenstruationPeriodRecord::class),
    RecordTypeInfo("Nutrition", NutritionRecord::class),
    RecordTypeInfo("OvulationTest", OvulationTestRecord::class),
    RecordTypeInfo("OxygenSaturation", OxygenSaturationRecord::class),
    RecordTypeInfo("Power", PowerRecord::class),
    RecordTypeInfo("RespiratoryRate", RespiratoryRateRecord::class),
    RecordTypeInfo("RestingHeartRate", RestingHeartRateRecord::class),
    RecordTypeInfo("SleepSession", SleepSessionRecord::class),
    RecordTypeInfo("Speed", SpeedRecord::class),
    RecordTypeInfo("Steps", StepsRecord::class),
    RecordTypeInfo("StepsCadence", StepsCadenceRecord::class),
    RecordTypeInfo("TotalCaloriesBurned", TotalCaloriesBurnedRecord::class),
    RecordTypeInfo("Vo2Max", Vo2MaxRecord::class),
    RecordTypeInfo("Weight", WeightRecord::class),
)
