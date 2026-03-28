package com.wake.dtn.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromBundleType(type: BundleType): String = type.name

    @TypeConverter
    fun toBundleType(name: String): BundleType = BundleType.valueOf(name)
}
