package com.example.boltassist

import org.osmdroid.util.GeoPoint

data class CityConfig(
    val name: String,
    val center: GeoPoint,
    val mainStation: GeoPoint? = null // Main train/bus station if different from center
)

object MapConfig {
    private val cities = mapOf(
        "Olsztyn" to CityConfig(
            name = "Olsztyn",
            center = GeoPoint(53.7784, 20.4801), // City center
            mainStation = GeoPoint(53.7735, 20.4842) // Dworzec Główny PKP
        ),
        "Warsaw" to CityConfig(
            name = "Warsaw", 
            center = GeoPoint(52.2297, 21.0122),
            mainStation = GeoPoint(52.2288, 21.0033) // Warszawa Centralna
        ),
        "Krakow" to CityConfig(
            name = "Krakow",
            center = GeoPoint(50.0647, 19.9450),
            mainStation = GeoPoint(50.0675, 19.9447) // Kraków Główny
        ),
        "Gdansk" to CityConfig(
            name = "Gdansk",
            center = GeoPoint(54.3520, 18.6466),
            mainStation = GeoPoint(54.3592, 18.6414) // Gdańsk Główny
        ),
        "Wroclaw" to CityConfig(
            name = "Wroclaw",
            center = GeoPoint(51.1079, 17.0385),
            mainStation = GeoPoint(51.0989, 17.0364) // Wrocław Główny
        )
    )
    
    fun getCityConfig(cityName: String): CityConfig {
        return cities[cityName] ?: cities["Olsztyn"]!!
    }
    
    fun getOperationCenter(cityName: String): GeoPoint {
        val config = getCityConfig(cityName)
        // Prefer main station for ride-sharing operations, fall back to city center
        return config.mainStation ?: config.center
    }
    
    fun getAllCityNames(): List<String> {
        return cities.keys.toList()
    }
} 