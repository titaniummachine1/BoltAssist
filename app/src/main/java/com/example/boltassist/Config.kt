package com.example.boltassist

/**
 * Central place for application-wide tunable constants.  
 * Keeping them here improves maintainability – they can be reused across
 * modules without introducing "magic numbers" sprinkled around the codebase.
 */
object AppConfig {
    /**
     * Average point-to-point driving speed in km/h that we use for ETA
     * calculations when we cannot determine a better value from real traffic
     * measurements.  30 km/h is a realistic urban average when accounting for
     * traffic lights and congestion.
     */
    const val AVERAGE_DRIVER_SPEED_KMH: Double = 30.0
} 