package com.example.signaltestenhanced

import org.json.JSONObject

/**
 * Enhanced data class to encapsulate comprehensive cell metrics
 * including SINR and Cell ID for both 4G and 5G networks
 */
data class CellMetrics(
    // 4G/LTE metrics
    val signalStrength4G: Int = -999,
    val sinr4G: Double = -999.0,
    val cellId4G: Int = -1,
    val pci4G: Int = -1,
    
    // 5G/NR metrics
    val signalStrength5G: Int = -999,
    val sinr5G: Double = -999.0,
    val cellId5G: Long = -1L,
    val pci5G: Int = -1,
    
    // General properties
    val is5G: Boolean = false,
    val networkType: String = "Unknown",
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Creates an empty/invalid CellMetrics instance
         */
        fun empty(): CellMetrics = CellMetrics()
        
        /**
         * Validates SINR value is within expected range
         * LTE RSSNR: typically -20 to +30 dB
         * 5G SS-SINR: typically -23 to +40 dB
         */
        fun isValidSinr(sinr: Double): Boolean = sinr != -999.0 && sinr >= -30.0 && sinr <= 50.0
        
        /**
         * Validates 4G Cell ID is within valid range
         * LTE CI: 0 to 268,435,455 (28-bit)
         */
        fun isValidCellId4G(cellId: Int): Boolean = cellId > 0 && cellId <= 268435455
        
        /**
         * Validates 5G Cell ID is within valid range
         * 5G NCI: 0 to 68,719,476,735 (36-bit)
         */
        fun isValidCellId5G(cellId: Long): Boolean = cellId > 0L && cellId <= 68719476735L
        
        /**
         * Validates Physical Cell ID
         * LTE PCI: 0-503
         * 5G PCI: 0-1007
         */
        fun isValidPci(pci: Int): Boolean = pci >= 0 && pci <= 1007
    }
    
    /**
     * Checks if this metrics instance contains valid data
     */
    fun isValid(): Boolean {
        return signalStrength4G != -999 || signalStrength5G != -999
    }
    
    /**
     * Returns validated metrics with range checking
     */
    fun validated(): CellMetrics {
        return copy(
            sinr4G = if (isValidSinr(sinr4G)) sinr4G else -999.0,
            cellId4G = if (isValidCellId4G(cellId4G)) cellId4G else -1,
            pci4G = if (isValidPci(pci4G)) pci4G else -1,
            sinr5G = if (isValidSinr(sinr5G)) sinr5G else -999.0,
            cellId5G = if (isValidCellId5G(cellId5G)) cellId5G else -1L,
            pci5G = if (isValidPci(pci5G)) pci5G else -1
        )
    }
    
    /**
     * Converts to JSON object for server transmission
     */
    fun toJsonObject(): JSONObject {
        val validated = validated()
        return JSONObject().apply {
            // 4G metrics
            if (validated.signalStrength4G != -999) {
                put("signal_strength_4g", validated.signalStrength4G)
                if (validated.sinr4G != -999.0) {
                    put("sinr_4g", validated.sinr4G)
                }
                if (validated.cellId4G != -1) {
                    put("cell_id_4g", validated.cellId4G)
                }
                if (validated.pci4G != -1) {
                    put("pci_4g", validated.pci4G)
                }
            }
            
            // 5G metrics
            if (validated.signalStrength5G != -999) {
                put("signal_strength_5g", validated.signalStrength5G)
                if (validated.sinr5G != -999.0) {
                    put("sinr_5g", validated.sinr5G)
                }
                if (validated.cellId5G != -1L) {
                    put("cell_id_5g", validated.cellId5G)
                }
                if (validated.pci5G != -1) {
                    put("pci_5g", validated.pci5G)
                }
            }
            
            // Metadata
            put("is_5g", is5G)
            put("network_type", networkType)
            put("timestamp", timestamp)
        }
    }
    
    override fun toString(): String {
        return "CellMetrics(4G: ${signalStrength4G}dBm, SINR: ${sinr4G}dB, Cell: ${cellId4G}, " +
                "5G: ${signalStrength5G}dBm, SINR: ${sinr5G}dB, Cell: ${cellId5G}, " +
                "is5G: ${is5G}, type: ${networkType})"
    }
}
