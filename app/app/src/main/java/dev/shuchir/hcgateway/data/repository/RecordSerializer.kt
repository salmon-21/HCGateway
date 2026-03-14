package dev.shuchir.hcgateway.data.repository

import androidx.health.connect.client.records.*
import androidx.health.connect.client.units.*
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset

/**
 * Serializes Health Connect Record objects to JSON matching the format
 * expected by the HCGateway API (same as the React Native bridge output).
 *
 * Key rules:
 * - startTime/endTime/time → ISO 8601 strings
 * - metadata.id → string UUID
 * - metadata.dataOrigin → package name string (not an object)
 */
object RecordSerializer {

    fun serializeRecords(records: List<Record>): JsonElement {
        val array = JsonArray()
        for (record in records) {
            array.add(serializeRecord(record))
        }
        return array
    }

    private fun serializeRecord(record: Record): JsonObject {
        val json = JsonObject()

        // Metadata
        val metadata = JsonObject()
        metadata.addProperty("id", record.metadata.id)
        metadata.addProperty("dataOrigin", record.metadata.dataOrigin.packageName)
        json.add("metadata", metadata)

        // Serialize based on record type
        when (record) {
            is ActiveCaloriesBurnedRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                json.addProperty("energy", record.energy.inKilocalories)
            }
            is BasalBodyTemperatureRecord -> {
                json.addInstant(record.time)
                json.addProperty("temperature", record.temperature.inCelsius)
                json.addProperty("measurementLocation", record.measurementLocation)
            }
            is BasalMetabolicRateRecord -> {
                json.addInstant(record.time)
                json.addProperty("basalMetabolicRate", record.basalMetabolicRate.inKilocaloriesPerDay)
            }
            is BloodGlucoseRecord -> {
                json.addInstant(record.time)
                json.addProperty("level", record.level.inMillimolesPerLiter)
                json.addProperty("specimenSource", record.specimenSource)
                json.addProperty("mealType", record.mealType)
                json.addProperty("relationToMeal", record.relationToMeal)
            }
            is BloodPressureRecord -> {
                json.addInstant(record.time)
                json.addProperty("systolic", record.systolic.inMillimetersOfMercury)
                json.addProperty("diastolic", record.diastolic.inMillimetersOfMercury)
                json.addProperty("bodyPosition", record.bodyPosition)
                json.addProperty("measurementLocation", record.measurementLocation)
            }
            is BodyFatRecord -> {
                json.addInstant(record.time)
                json.addProperty("percentage", record.percentage.value)
            }
            is BodyTemperatureRecord -> {
                json.addInstant(record.time)
                json.addProperty("temperature", record.temperature.inCelsius)
                json.addProperty("measurementLocation", record.measurementLocation)
            }
            is BoneMassRecord -> {
                json.addInstant(record.time)
                json.addProperty("mass", record.mass.inKilograms)
            }
            is CervicalMucusRecord -> {
                json.addInstant(record.time)
                json.addProperty("appearance", record.appearance)
                json.addProperty("sensation", record.sensation)
            }
            is CyclingPedalingCadenceRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                val samples = JsonArray()
                for (sample in record.samples) {
                    val s = JsonObject()
                    s.addProperty("time", sample.time.toString())
                    s.addProperty("revolutionsPerMinute", sample.revolutionsPerMinute)
                    samples.add(s)
                }
                json.add("samples", samples)
            }
            is DistanceRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                json.addProperty("distance", record.distance.inMeters)
            }
            is ElevationGainedRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                json.addProperty("elevation", record.elevation.inMeters)
            }
            is ExerciseSessionRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                json.addProperty("exerciseType", record.exerciseType)
                record.title?.let { json.addProperty("title", it) }
                record.notes?.let { json.addProperty("notes", it) }
            }
            is FloorsClimbedRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                json.addProperty("floors", record.floors)
            }
            is HeartRateRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                val samples = JsonArray()
                for (sample in record.samples) {
                    val s = JsonObject()
                    s.addProperty("time", sample.time.toString())
                    s.addProperty("beatsPerMinute", sample.beatsPerMinute)
                    samples.add(s)
                }
                json.add("samples", samples)
            }
            is HeightRecord -> {
                json.addInstant(record.time)
                json.addProperty("height", record.height.inMeters)
            }
            is HydrationRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                json.addProperty("volume", record.volume.inLiters)
            }
            is IntermenstrualBleedingRecord -> {
                json.addInstant(record.time)
            }
            is LeanBodyMassRecord -> {
                json.addInstant(record.time)
                json.addProperty("mass", record.mass.inKilograms)
            }
            is MenstruationFlowRecord -> {
                json.addInstant(record.time)
                json.addProperty("flow", record.flow)
            }
            is MenstruationPeriodRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
            }
            is NutritionRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                record.name?.let { json.addProperty("name", it) }
                record.energy?.let { json.addProperty("energy", it.inKilocalories) }
                record.totalFat?.let { json.addProperty("totalFat", it.inGrams) }
                record.totalCarbohydrate?.let { json.addProperty("totalCarbohydrate", it.inGrams) }
                record.protein?.let { json.addProperty("protein", it.inGrams) }
                record.dietaryFiber?.let { json.addProperty("dietaryFiber", it.inGrams) }
                record.sugar?.let { json.addProperty("sugar", it.inGrams) }
                record.sodium?.let { json.addProperty("sodium", it.inGrams) }
                record.potassium?.let { json.addProperty("potassium", it.inGrams) }
                record.cholesterol?.let { json.addProperty("cholesterol", it.inGrams) }
                record.saturatedFat?.let { json.addProperty("saturatedFat", it.inGrams) }
                record.unsaturatedFat?.let { json.addProperty("unsaturatedFat", it.inGrams) }
                record.calcium?.let { json.addProperty("calcium", it.inGrams) }
                record.iron?.let { json.addProperty("iron", it.inGrams) }
                record.vitaminA?.let { json.addProperty("vitaminA", it.inGrams) }
                record.vitaminC?.let { json.addProperty("vitaminC", it.inGrams) }
                record.mealType?.let { json.addProperty("mealType", it) }
            }
            is OvulationTestRecord -> {
                json.addInstant(record.time)
                json.addProperty("result", record.result)
            }
            is OxygenSaturationRecord -> {
                json.addInstant(record.time)
                json.addProperty("percentage", record.percentage.value)
            }
            is PowerRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                val samples = JsonArray()
                for (sample in record.samples) {
                    val s = JsonObject()
                    s.addProperty("time", sample.time.toString())
                    s.addProperty("power", sample.power.inWatts)
                    samples.add(s)
                }
                json.add("samples", samples)
            }
            is RespiratoryRateRecord -> {
                json.addInstant(record.time)
                json.addProperty("rate", record.rate)
            }
            is RestingHeartRateRecord -> {
                json.addInstant(record.time)
                json.addProperty("beatsPerMinute", record.beatsPerMinute)
            }
            is SleepSessionRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                record.title?.let { json.addProperty("title", it) }
                record.notes?.let { json.addProperty("notes", it) }
                val stages = JsonArray()
                for (stage in record.stages) {
                    val s = JsonObject()
                    s.addProperty("startTime", stage.startTime.toString())
                    s.addProperty("endTime", stage.endTime.toString())
                    s.addProperty("stage", stage.stage)
                    stages.add(s)
                }
                json.add("stages", stages)
            }
            is SpeedRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                val samples = JsonArray()
                for (sample in record.samples) {
                    val s = JsonObject()
                    s.addProperty("time", sample.time.toString())
                    s.addProperty("speed", sample.speed.inMetersPerSecond)
                    samples.add(s)
                }
                json.add("samples", samples)
            }
            is StepsRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                json.addProperty("count", record.count)
            }
            is StepsCadenceRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                val samples = JsonArray()
                for (sample in record.samples) {
                    val s = JsonObject()
                    s.addProperty("time", sample.time.toString())
                    s.addProperty("rate", sample.rate)
                    samples.add(s)
                }
                json.add("samples", samples)
            }
            is TotalCaloriesBurnedRecord -> {
                json.addTimeRange(record.startTime, record.endTime)
                json.addProperty("energy", record.energy.inKilocalories)
            }
            is Vo2MaxRecord -> {
                json.addInstant(record.time)
                json.addProperty("vo2MillilitersPerMinuteKilogram", record.vo2MillilitersPerMinuteKilogram)
                json.addProperty("measurementMethod", record.measurementMethod)
            }
            is WeightRecord -> {
                json.addInstant(record.time)
                json.addProperty("weight", record.weight.inKilograms)
            }
            else -> {
                // Unknown record type — skip time fields
            }
        }

        return json
    }

    private fun JsonObject.addTimeRange(startTime: Instant, endTime: Instant) {
        addProperty("startTime", startTime.toString())
        addProperty("endTime", endTime.toString())
    }

    private fun JsonObject.addInstant(time: Instant) {
        addProperty("time", time.toString())
    }
}
