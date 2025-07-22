package com.example.signaltestenhanced

import android.os.Build
import android.telephony.*
import android.util.Log

/**
 * Utility class for enhanced signal collection with thread safety
 * and comprehensive error handling
 */
object SignalCollectionUtils {
    private const val TAG = "SignalCollectionUtils"
    
    /**
     * Main method to collect enhanced signal metrics with SINR and Cell ID
     * This is the primary entry point called by MainActivity
     */
    fun collectEnhancedSignalMetrics(telephonyManager: TelephonyManager): CellMetrics {
        return try {
            val cellInfoList = getAllCellInfoSafely(telephonyManager)
            if (cellInfoList.isEmpty()) {
                Log.w(TAG, "No cell info available")
                return CellMetrics.empty()
            }
            
            // Find the best serving cell from available cells
            val bestCell = findBestServingCell(cellInfoList)
            Log.d(TAG, "Enhanced metrics collected: $bestCell")
            bestCell
            
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting enhanced signal metrics", e)
            CellMetrics.empty()
        }
    }
    
    /**
     * Safely collects cell metrics from CellInfo with proper API level checks
     * and comprehensive error handling
     */
    fun collectCellMetrics(cellInfo: CellInfo): CellMetrics {
        return try {
            when (cellInfo) {
                is CellInfoLte -> collectLteMetrics(cellInfo)
                is CellInfoNr -> collectNrMetrics(cellInfo)
                else -> {
                    Log.d(TAG, "Unsupported cell type: ${cellInfo.javaClass.simpleName}")
                    CellMetrics.empty()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting cell metrics: ${e.message}", e)
            CellMetrics.empty()
        }
    }
    
    /**
     * Collects LTE (4G) specific metrics
     */
    private fun collectLteMetrics(cellInfo: CellInfoLte): CellMetrics {
        return try {
            val lteSignal = cellInfo.cellSignalStrength as CellSignalStrengthLte
            val lteIdentity = cellInfo.cellIdentity as CellIdentityLte
            
            val sinr = try {
                lteSignal.rssnr
            } catch (e: Exception) {
                Log.w(TAG, "LTE RSSNR not available: ${e.message}")
                -999
            }
            
            val cellId = try {
                lteIdentity.ci.toLong()
            } catch (e: Exception) {
                Log.w(TAG, "LTE Cell ID not available: ${e.message}")
                -1L
            }
            
            val pci = try {
                lteIdentity.pci
            } catch (e: Exception) {
                Log.w(TAG, "LTE PCI not available: ${e.message}")
                -1
            }
            
            val metrics = CellMetrics(
                signalStrength4G = lteSignal.rsrp,
                sinr4G = sinr.toDouble(),
                cellId4G = cellId.toInt(),
                pci4G = pci,
                networkType = "LTE"
            )
            
            Log.d(TAG, "LTE Metrics - SINR: ${sinr}dB, CellID: $cellId, PCI: $pci, Registered: ${cellInfo.isRegistered}")
            return metrics.validated()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting LTE metrics: ${e.message}", e)
            CellMetrics.empty()
        }
    }
    
    /**
     * Collects NR (5G) specific metrics with API level checks
     */
    private fun collectNrMetrics(cellInfo: CellInfoNr): CellMetrics {
        return try {
            // Check API level for 5G NR support
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Log.w(TAG, "5G NR metrics require API 29+, current: ${Build.VERSION.SDK_INT}")
                return CellMetrics.empty()
            }
            
            val nrSignal = cellInfo.cellSignalStrength as CellSignalStrengthNr
            val nrIdentity = cellInfo.cellIdentity as CellIdentityNr
            
            val sinr = try {
                nrSignal.ssSinr
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "5G SS-SINR not supported on this device: ${e.message}")
                -999
            } catch (e: Exception) {
                Log.w(TAG, "5G SS-SINR not available: ${e.message}")
                -999
            }
            
            val cellId = try {
                nrIdentity.nci
            } catch (e: Exception) {
                Log.w(TAG, "5G NCI not available: ${e.message}")
                -1L
            }
            
            val pci = try {
                nrIdentity.pci
            } catch (e: Exception) {
                Log.w(TAG, "5G PCI not available: ${e.message}")
                -1
            }
            
            val metrics = CellMetrics(
                signalStrength5G = nrSignal.ssRsrp,
                sinr5G = sinr.toDouble(),
                cellId5G = cellId,
                pci5G = pci,
                is5G = true,
                networkType = "NR"
            )
            
            Log.d(TAG, "5G Metrics - SINR: ${sinr}dB, CellID: $cellId, PCI: $pci, Registered: ${cellInfo.isRegistered}")
            return metrics.validated()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting 5G metrics: ${e.message}", e)
            CellMetrics.empty()
        }
    }
    
    /**
     * Safely gets all cell info with proper error handling
     */
    fun getAllCellInfoSafely(telephonyManager: TelephonyManager): List<CellInfo> {
        return try {
            telephonyManager.allCellInfo ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for cell info: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cell info: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Finds the best serving cell metrics from a list of cell info
     * Returns comprehensive CellMetrics with both 4G and 5G data
     */
    fun findBestServingCell(cellInfoList: List<CellInfo>): CellMetrics {
        var best4GSignal = -999
        var best5GSignal = -999
        var cellMetrics4G: CellMetrics? = null
        var cellMetrics5G: CellMetrics? = null
        var has5G = false
        
        for (cellInfo in cellInfoList) {
            if (!cellInfo.isRegistered) continue
            
            when (cellInfo) {
                is CellInfoLte -> {
                    try {
                        val lteSignal = cellInfo.cellSignalStrength as CellSignalStrengthLte
                        val lteIdentity = cellInfo.cellIdentity as CellIdentityLte
                        val rsrp = lteSignal.rsrp
                        
                        if (rsrp != Int.MAX_VALUE && rsrp > best4GSignal) {
                            best4GSignal = rsrp
                            
                            val sinr = try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    lteSignal.rssnr.toDouble()
                                } else {
                                    -999.0
                                }
                            } catch (e: Exception) {
                                -999.0
                            }
                            
                            val cellId = try {
                                lteIdentity.ci
                            } catch (e: Exception) {
                                -1
                            }
                            
                            val pci = try {
                                lteIdentity.pci
                            } catch (e: Exception) {
                                -1
                            }
                            
                            cellMetrics4G = CellMetrics(
                                signalStrength4G = rsrp,
                                sinr4G = sinr,
                                cellId4G = cellId,
                                pci4G = pci,
                                networkType = "LTE"
                            )
                            
                            Log.d(TAG, "4G metrics: RSRP=${rsrp}dBm, SINR=${sinr}dB, Cell=${cellId}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing LTE cell", e)
                    }
                }
                
                is CellInfoNr -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val nrSignal = cellInfo.cellSignalStrength as CellSignalStrengthNr
                            val nrIdentity = cellInfo.cellIdentity as CellIdentityNr
                            val ssRsrp = nrSignal.ssRsrp
                            
                            if (ssRsrp != Int.MAX_VALUE && ssRsrp > best5GSignal) {
                                best5GSignal = ssRsrp
                                has5G = true
                                
                                val sinr = try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        nrSignal.ssSinr.toDouble()
                                    } else {
                                        -999.0
                                    }
                                } catch (e: Exception) {
                                    -999.0
                                }
                                
                                val cellId = try {
                                    nrIdentity.nci
                                } catch (e: Exception) {
                                    -1L
                                }
                                
                                val pci = try {
                                    nrIdentity.pci
                                } catch (e: Exception) {
                                    -1
                                }
                                
                                cellMetrics5G = CellMetrics(
                                    signalStrength5G = ssRsrp,
                                    sinr5G = sinr,
                                    cellId5G = cellId,
                                    pci5G = pci,
                                    is5G = true,
                                    networkType = "NR"
                                )
                                
                                Log.d(TAG, "5G metrics: SS-RSRP=${ssRsrp}dBm, SINR=${sinr}dB, Cell=${cellId}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing NR cell", e)
                        }
                    }
                }
            }
        }
        
        // Combine the best 4G and 5G metrics into a single CellMetrics object
        return when {
            cellMetrics4G != null && cellMetrics5G != null -> {
                // Both 4G and 5G available - combine them
                CellMetrics(
                    signalStrength4G = cellMetrics4G.signalStrength4G,
                    sinr4G = cellMetrics4G.sinr4G,
                    cellId4G = cellMetrics4G.cellId4G,
                    pci4G = cellMetrics4G.pci4G,
                    signalStrength5G = cellMetrics5G.signalStrength5G,
                    sinr5G = cellMetrics5G.sinr5G,
                    cellId5G = cellMetrics5G.cellId5G,
                    pci5G = cellMetrics5G.pci5G,
                    is5G = true,
                    networkType = "5G NSA"
                )
            }
            cellMetrics5G != null -> {
                // Only 5G available
                cellMetrics5G
            }
            cellMetrics4G != null -> {
                // Only 4G available
                cellMetrics4G
            }
            else -> {
                // No valid metrics found
                Log.w(TAG, "No valid serving cell metrics found")
                CellMetrics.empty()
            }
        }
    }
}
