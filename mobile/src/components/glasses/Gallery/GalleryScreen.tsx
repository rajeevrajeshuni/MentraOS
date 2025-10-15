/**
 * Main gallery screen component
 */

import {useCallback, useState, useEffect, useMemo, useRef} from "react"
import {View, BackHandler, TouchableOpacity, ActivityIndicator, Dimensions, FlatList, ViewToken} from "react-native"
import {useFocusEffect} from "expo-router"
import {useAppTheme} from "@/utils/useAppTheme"
import {spacing, ThemedStyle} from "@/theme"
import {ViewStyle, TextStyle, ImageStyle} from "react-native"
import {useCoreStatus} from "@/contexts/CoreStatusProvider"
import {useNavigationHistory} from "@/contexts/NavigationHistoryContext"
import {PhotoInfo} from "../../../types/asg"
import {asgCameraApi} from "../../../services/asg/asgCameraApi"
import {localStorageService} from "../../../services/asg/localStorageService"
import {PhotoImage} from "./PhotoImage"
import {MediaViewer} from "./MediaViewer"
import {ProgressRing} from "./ProgressRing"
import {createShimmerPlaceholder} from "react-native-shimmer-placeholder"
import LinearGradient from "expo-linear-gradient"

// @ts-ignore
const ShimmerPlaceholder = createShimmerPlaceholder(LinearGradient)
import showAlert from "@/utils/AlertUtils"
import {translate} from "@/i18n"
import {shareFile} from "@/utils/FileUtils"
import MaterialCommunityIcons from "react-native-vector-icons/MaterialCommunityIcons"
import bridge from "@/bridge/MantleBridge"
import WifiManager from "react-native-wifi-reborn"
import GlobalEventEmitter from "@/utils/GlobalEventEmitter"
import {networkConnectivityService, NetworkStatus} from "@/services/asg/networkConnectivityService"
import {Header, Text} from "@/components/ignite"
import * as Linking from "expo-linking"
import {MediaLibraryPermissions} from "@/utils/MediaLibraryPermissions"
import {gallerySettingsService} from "@/services/asg/gallerySettingsService"
import {hasGallery} from "@/config/glassesFeatures"

// Gallery timing constants
const TIMING = {
  PROGRESS_RING_DISPLAY_MS: 3000, // How long to show completed/failed progress rings
  SYNC_COMPLETE_DISPLAY_MS: 1000, // How long to show "Sync complete!" message
  ALERT_DELAY_MS: 100, // Delay before showing alerts to allow UI to settle
  HOTSPOT_LOAD_DELAY_MS: 500, // Delay before loading photos after hotspot connects
  RETRY_AFTER_RATE_LIMIT_MS: 5000, // Delay before retrying after 429 rate limit
} as const

// Gallery state machine states
enum GalleryState {
  INITIALIZING = "initializing",
  QUERYING_GLASSES = "querying_glasses",
  NO_MEDIA_ON_GLASSES = "no_media_on_glasses",
  MEDIA_AVAILABLE = "media_available",
  REQUESTING_HOTSPOT = "requesting_hotspot",
  WAITING_FOR_WIFI_PROMPT = "waiting_for_wifi_prompt",
  USER_CANCELLED_WIFI = "user_cancelled_wifi",
  CONNECTING_TO_HOTSPOT = "connecting_to_hotspot",
  CONNECTED_LOADING = "connected_loading",
  READY_TO_SYNC = "ready_to_sync",
  SYNCING = "syncing",
  SYNC_COMPLETE = "sync_complete",
  ERROR = "error",
}

interface GalleryItem {
  id: string
  type: "server" | "local" | "placeholder"
  index: number
  photo?: PhotoInfo
  isOnServer?: boolean
}

export function GalleryScreen() {
  const {status} = useCoreStatus()
  const {goBack, push} = useNavigationHistory()
  const {theme, themed} = useAppTheme()

  // Column calculation - 3 per row like Google Photos / Apple Photos
  const screenWidth = Dimensions.get("window").width
  const ITEM_SPACING = 2 // Minimal spacing between items (1-2px hairline)
  const numColumns = screenWidth < 320 ? 2 : 3 // 2 columns for very small screens, otherwise 3
  const itemWidth = (screenWidth - ITEM_SPACING * (numColumns - 1)) / numColumns

  const [networkStatus] = useState<NetworkStatus>(networkConnectivityService.getStatus())

  // Memoize connection values
  const connectionInfo = useMemo(() => {
    const glassesInfo = status.glasses_info
    if (!glassesInfo) return {}

    return {
      isHotspotEnabled: glassesInfo.glasses_hotspot_enabled,
      hotspotSsid: glassesInfo.glasses_hotspot_ssid,
      hotspotPassword: glassesInfo.glasses_hotspot_password,
      hotspotGatewayIp: glassesInfo.glasses_hotspot_gateway_ip,
    }
  }, [status.glasses_info])

  const {hotspotSsid, hotspotPassword, hotspotGatewayIp, isHotspotEnabled} = connectionInfo

  // Permission state
  const [hasMediaLibraryPermission, setHasMediaLibraryPermission] = useState(false)
  const [isRequestingPermission, setIsRequestingPermission] = useState(false)

  // State machine
  const [galleryState, setGalleryState] = useState<GalleryState>(GalleryState.INITIALIZING)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const transitionToState = (newState: GalleryState) => {
    console.log(`[GalleryScreen] State transition: ${galleryState} → ${newState}`)
    setGalleryState(newState)
  }

  // Data state
  const [totalServerCount, setTotalServerCount] = useState(0)
  const [loadedServerPhotos, setLoadedServerPhotos] = useState<Map<number, PhotoInfo>>(new Map())
  const [downloadedPhotos, setDownloadedPhotos] = useState<PhotoInfo[]>([])
  const [selectedPhoto, setSelectedPhoto] = useState<PhotoInfo | null>(null)
  const [syncProgress, setSyncProgress] = useState<{
    current: number
    total: number
    message: string
    fileProgress?: number
  } | null>(null)
  const [photoSyncStates, setPhotoSyncStates] = useState<
    Map<
      string,
      {
        status: "pending" | "downloading" | "completed" | "failed"
        progress: number
      }
    >
  >(new Map())
  const [glassesGalleryStatus, setGlassesGalleryStatus] = useState<{
    photos: number
    videos: number
    total: number
    has_content: boolean
  } | null>(null)

  // Track if gallery opened the hotspot
  const galleryOpenedHotspotRef = useRef(false)
  const [galleryOpenedHotspot, setGalleryOpenedHotspot] = useState(false)

  // Track loaded ranges
  const loadedRanges = useRef<Set<string>>(new Set())
  const loadingRanges = useRef<Set<string>>(new Set())
  const syncTriggeredRef = useRef(false)
  const PAGE_SIZE = 20

  // Load downloaded photos
  const loadDownloadedPhotos = useCallback(async () => {
    try {
      const downloadedFiles = await localStorageService.getDownloadedFiles()
      const photoInfos = Object.values(downloadedFiles).map(file => localStorageService.convertToPhotoInfo(file))
      setDownloadedPhotos(photoInfos)
    } catch (err) {
      console.error("Error loading downloaded photos:", err)
    }
  }, [])

  // Initial load - get total count and first batch
  const loadInitialPhotos = useCallback(
    async (overrideServerIp?: string) => {
      const serverIp = overrideServerIp || hotspotGatewayIp
      const hasConnection = overrideServerIp || (isHotspotEnabled && hotspotGatewayIp)

      if (!hasConnection || !serverIp) {
        console.log("[GalleryScreen] Glasses not connected")
        setTotalServerCount(0)
        if (galleryState === GalleryState.CONNECTED_LOADING) {
          transitionToState(GalleryState.NO_MEDIA_ON_GLASSES)
        }
        return
      }

      if (galleryState !== GalleryState.CONNECTED_LOADING) {
        transitionToState(GalleryState.CONNECTED_LOADING)
      }

      try {
        asgCameraApi.setServer(serverIp, 8089)
        const result = await asgCameraApi.getGalleryPhotos(PAGE_SIZE, 0)

        setTotalServerCount(result.totalCount)

        if (result.totalCount === 0) {
          console.log("[GalleryScreen] No photos on glasses")
          transitionToState(GalleryState.NO_MEDIA_ON_GLASSES)
          return
        }

        const newMap = new Map<number, PhotoInfo>()
        result.photos.forEach((photo, index) => {
          newMap.set(index, photo)
        })
        setLoadedServerPhotos(newMap)
        loadedRanges.current.add("0-19")
        transitionToState(GalleryState.READY_TO_SYNC)
      } catch (err) {
        console.error("[GalleryScreen] Failed to load initial photos:", err)
        setTotalServerCount(0)

        let errorMsg = "Failed to load photos"
        if (err instanceof Error) {
          if (err.message.includes("429")) {
            errorMsg = "Server is busy, please try again in a moment"
            setTimeout(() => {
              if (galleryState === GalleryState.ERROR) {
                console.log("[GalleryScreen] Retrying after rate limit...")
                transitionToState(GalleryState.CONNECTED_LOADING)
                loadInitialPhotos()
              }
            }, TIMING.RETRY_AFTER_RATE_LIMIT_MS)
          } else if (err.message.includes("400")) {
            errorMsg = "Invalid request to server"
          } else {
            errorMsg = err.message
          }
        }

        setErrorMessage(errorMsg)
        transitionToState(GalleryState.ERROR)
      }
    },
    [galleryState, isHotspotEnabled, hotspotGatewayIp],
  )

  // Load photos for specific indices
  const loadPhotosForIndices = useCallback(
    async (indices: number[]) => {
      if (!isHotspotEnabled || !hotspotGatewayIp || indices.length === 0) return

      const unloadedIndices = indices.filter(i => !loadedServerPhotos.has(i))
      if (unloadedIndices.length === 0) return

      const sortedIndices = [...unloadedIndices].sort((a, b) => a - b)
      const minIndex = sortedIndices[0]
      const maxIndex = sortedIndices[sortedIndices.length - 1]
      const rangeKey = `${minIndex}-${maxIndex}`

      if (loadingRanges.current.has(rangeKey) || loadedRanges.current.has(rangeKey)) return

      loadingRanges.current.add(rangeKey)

      try {
        asgCameraApi.setServer(hotspotGatewayIp, 8089)
        const limit = maxIndex - minIndex + 1
        const result = await asgCameraApi.getGalleryPhotos(limit, minIndex)

        setLoadedServerPhotos(prev => {
          const newMap = new Map(prev)
          result.photos.forEach((photo, i) => {
            newMap.set(minIndex + i, photo)
          })
          return newMap
        })

        loadedRanges.current.add(rangeKey)
      } catch (err) {
        console.error(`[GalleryScreen] Failed to load range ${rangeKey}:`, err)
      } finally {
        loadingRanges.current.delete(rangeKey)
      }
    },
    [isHotspotEnabled, hotspotGatewayIp, loadedServerPhotos],
  )

  // Sync files from server
  const handleSync = async () => {
    const hasConnection = isHotspotEnabled && hotspotGatewayIp
    const serverIp = hotspotGatewayIp

    if (!hasConnection || !serverIp) {
      showAlert("Cannot Sync", "Your glasses are not connected. Please connect them to WiFi or enable hotspot.", [
        {text: translate("common:ok")},
      ])
      return
    }

    transitionToState(GalleryState.SYNCING)
    setSyncProgress(null)

    try {
      console.log(`[GalleryScreen] Starting sync with server IP: ${serverIp}`)
      asgCameraApi.setServer(serverIp, 8089)

      const syncState = await localStorageService.getSyncState()
      const syncResponse = await asgCameraApi.syncWithServer(syncState.client_id, syncState.last_sync_time, true)

      const syncData = syncResponse.data || syncResponse

      if (!syncData.changed_files || syncData.changed_files.length === 0) {
        console.log("Sync Complete - no new files")
        // Stop hotspot if gallery opened it
        if (galleryOpenedHotspot) {
          console.log("[GalleryScreen] No files to sync, closing hotspot...")
          await handleStopHotspot()
        }
        transitionToState(GalleryState.SYNC_COMPLETE)
        return
      }

      // Initialize photo sync states
      const initialSyncStates = new Map()
      syncData.changed_files.forEach(photo => {
        initialSyncStates.set(photo.name, {
          status: "pending" as const,
          progress: 0,
        })
      })
      setPhotoSyncStates(initialSyncStates)

      setSyncProgress({
        current: 0,
        total: syncData.changed_files.length,
        message: "Downloading files...",
      })

      const downloadResult = await asgCameraApi.batchSyncFiles(
        syncData.changed_files,
        true,
        (current, total, fileName, fileProgress) => {
          console.log(`[GalleryScreen] Progress callback: ${fileName} - ${fileProgress}% (${current}/${total})`)

          // Use requestAnimationFrame to ensure immediate UI updates
          requestAnimationFrame(() => {
            // Update individual photo progress
            setPhotoSyncStates(prev => {
              const newStates = new Map(prev)

              // Update the current file being downloaded
              newStates.set(fileName, {
                status: "downloading",
                progress: fileProgress || 0,
              })

              // Mark previously completed files as completed (if not already marked)
              for (let i = 0; i < current - 1; i++) {
                const completedFileName = syncData.changed_files[i]?.name
                if (completedFileName && !newStates.has(completedFileName)) {
                  newStates.set(completedFileName, {
                    status: "completed",
                    progress: 100,
                  })
                }
              }

              // If this is the last file and progress is 100%, mark it as completed
              if (current === total && fileProgress === 100) {
                newStates.set(fileName, {
                  status: "completed",
                  progress: 100,
                })
              }

              // Mark previous files as completed when moving to next file
              if (fileProgress === 0 && current > 1) {
                // Mark the previous file as completed
                const previousFileName = syncData.changed_files[current - 2]?.name
                if (previousFileName) {
                  newStates.set(previousFileName, {
                    status: "completed",
                    progress: 100,
                  })
                }
              }

              console.log(`[GalleryScreen] Updated sync states:`, Array.from(newStates.entries()))
              return newStates
            })

            setSyncProgress({
              current,
              total,
              message: `Downloading ${fileName}...`,
              fileProgress,
            })
          })
        },
      )

      const glassesModel = status.glasses_info?.model_name

      // Save downloaded files but keep progress states visible
      for (const photoInfo of downloadResult.downloaded) {
        const downloadedFile = localStorageService.convertToDownloadedFile(
          photoInfo,
          photoInfo.filePath || "",
          photoInfo.thumbnailPath,
          glassesModel,
        )
        await localStorageService.saveDownloadedFile(downloadedFile)
      }

      // Auto-save to camera roll if enabled
      const shouldAutoSave = await gallerySettingsService.getAutoSaveToCameraRoll()
      if (shouldAutoSave) {
        console.log("[GalleryScreen] Auto-saving photos to camera roll...")
        let savedCount = 0
        let failedCount = 0

        for (const photoInfo of downloadResult.downloaded) {
          const filePath = photoInfo.filePath || localStorageService.getPhotoFilePath(photoInfo.name)
          const success = await MediaLibraryPermissions.saveToLibrary(filePath)
          if (success) {
            savedCount++
          } else {
            failedCount++
          }
        }

        console.log(`[GalleryScreen] Saved ${savedCount}/${downloadResult.downloaded.length} photos to camera roll`)
        if (failedCount > 0) {
          console.warn(`[GalleryScreen] Failed to save ${failedCount} photos to camera roll`)
        }
      }

      // Keep all progress states visible for a moment to show completion
      setTimeout(() => {
        setPhotoSyncStates(new Map())
      }, TIMING.PROGRESS_RING_DISPLAY_MS)

      // Mark failed photos
      for (const failedFileName of downloadResult.failed) {
        setPhotoSyncStates(prev => {
          const newStates = new Map(prev)
          newStates.set(failedFileName, {
            status: "failed",
            progress: 0,
          })
          return newStates
        })
      }

      // Remove failed progress rings after a delay to show error state
      if (downloadResult.failed.length > 0) {
        setTimeout(() => {
          setPhotoSyncStates(prev => {
            const newStates = new Map(prev)
            for (const failedFileName of downloadResult.failed) {
              newStates.delete(failedFileName)
            }
            return newStates
          })
        }, TIMING.PROGRESS_RING_DISPLAY_MS)
      }

      // Files are now deleted immediately after each successful download in batchSyncFiles
      if (downloadResult.downloaded.length > 0) {
        console.log(
          `[GalleryScreen] Successfully synced ${downloadResult.downloaded.length} files (deleted from glasses after each download)`,
        )
      }

      await localStorageService.updateSyncState({
        last_sync_time: syncData.server_time,
        total_downloaded: syncState.total_downloaded + downloadResult.downloaded.length,
        total_size: syncState.total_size + downloadResult.total_size,
      })

      // Load downloaded photos first to ensure smooth transition
      await loadDownloadedPhotos()

      // Clear sync progress states (progress rings no longer needed)
      setPhotoSyncStates(new Map())
      setSyncProgress(null)

      // Show brief success state
      transitionToState(GalleryState.SYNC_COMPLETE)

      // Stop hotspot if gallery opened it
      if (galleryOpenedHotspot) {
        console.log("[GalleryScreen] Sync completed with files, closing hotspot...")
        try {
          await handleStopHotspot()
          console.log("[GalleryScreen] Hotspot closed successfully after sync")
        } catch (error) {
          console.error("[GalleryScreen] Failed to close hotspot after sync:", error)
        }
      }

      // Gradually clear server state after downloads are loaded
      setTimeout(() => {
        setLoadedServerPhotos(new Map())
        setTotalServerCount(0)
        loadedRanges.current.clear()
        loadingRanges.current.clear()
        transitionToState(GalleryState.NO_MEDIA_ON_GLASSES)
      }, TIMING.SYNC_COMPLETE_DISPLAY_MS)
    } catch (err) {
      let errorMsg = "Sync failed"
      if (err instanceof Error) {
        if (err.message.includes("429")) {
          errorMsg = "Server is busy, please try again in a moment"
        } else if (err.message.includes("400")) {
          errorMsg = "Invalid sync request"
        } else {
          errorMsg = err.message
        }
      }

      setErrorMessage(errorMsg)
      if (!errorMsg.includes("busy")) {
        showAlert("Sync Error", errorMsg, [{text: translate("common:ok")}])
      }
      transitionToState(GalleryState.ERROR)
      setSyncProgress(null)
    }
  }

  // Handle photo selection
  const handlePhotoPress = (item: GalleryItem) => {
    if (!item.photo) return

    // Prevent opening photos that are currently being synced
    const syncState = photoSyncStates.get(item.photo.name)
    if (
      syncState &&
      (syncState.status === "downloading" || syncState.status === "pending" || syncState.status === "completed")
    ) {
      console.log(`[GalleryScreen] Photo ${item.photo.name} is being synced, preventing open`)
      return
    }

    if (item.photo.is_video && item.isOnServer) {
      showAlert("Video Not Downloaded", "Please sync this video to your device to watch it", [
        {text: translate("common:ok")},
      ])
      return
    }
    setSelectedPhoto(item.photo)
  }

  // Handle photo sharing
  const handleSharePhoto = async (photo: PhotoInfo) => {
    if (!photo) {
      console.error("No photo provided to share")
      return
    }

    try {
      const shareUrl = photo.is_video && photo.download ? photo.download : photo.url
      let filePath = ""

      if (shareUrl?.startsWith("file://")) {
        filePath = shareUrl.replace("file://", "")
      } else if (photo.filePath) {
        filePath = photo.filePath.startsWith("file://") ? photo.filePath.replace("file://", "") : photo.filePath
      } else {
        const mediaType = photo.is_video ? "video" : "photo"
        setSelectedPhoto(null)
        setTimeout(() => {
          showAlert("Info", `Please sync this ${mediaType} first to share it`, [{text: translate("common:ok")}])
        }, TIMING.ALERT_DELAY_MS)
        return
      }

      if (!filePath) {
        console.error("No valid file path found")
        setSelectedPhoto(null)
        setTimeout(() => {
          showAlert("Error", "Unable to share this photo", [{text: translate("common:ok")}])
        }, TIMING.ALERT_DELAY_MS)
        return
      }

      let shareMessage = photo.is_video ? "Check out this video" : "Check out this photo"
      if (photo.glassesModel) {
        shareMessage += ` taken with ${photo.glassesModel}`
      }
      shareMessage += "!"

      const mimeType = photo.mime_type || (photo.is_video ? "video/mp4" : "image/jpeg")
      await shareFile(filePath, mimeType, "Share Photo", shareMessage)
      console.log("Share completed successfully")
    } catch (error) {
      if (error instanceof Error && error.message?.includes("FileProvider")) {
        setSelectedPhoto(null)
        setTimeout(() => {
          showAlert(
            "Sharing Not Available",
            "File sharing will work after the next app build. For now, you can find your photos in the AugmentOS folder.",
            [{text: translate("common:ok")}],
          )
        }, TIMING.ALERT_DELAY_MS)
      } else {
        console.error("Error sharing photo:", error)
        setSelectedPhoto(null)
        setTimeout(() => {
          showAlert("Error", "Failed to share photo", [{text: translate("common:ok")}])
        }, TIMING.ALERT_DELAY_MS)
      }
    }
  }

  // Handle hotspot request
  const handleRequestHotspot = async () => {
    transitionToState(GalleryState.REQUESTING_HOTSPOT)
    try {
      await bridge.sendCommand("set_hotspot_state", {enabled: true})
      setGalleryOpenedHotspot(true)
      galleryOpenedHotspotRef.current = true
      console.log("[GalleryScreen] Gallery initiated hotspot")
      transitionToState(GalleryState.WAITING_FOR_WIFI_PROMPT)
    } catch (error) {
      console.error("[GalleryScreen] Failed to start hotspot:", error)
      setErrorMessage("Failed to start hotspot")
      showAlert("Error", "Failed to start hotspot", [{text: "OK"}])
      transitionToState(GalleryState.ERROR)
    }
  }

  // Handle stop hotspot
  const handleStopHotspot = async () => {
    console.log("[GalleryScreen] Stopping hotspot...")
    try {
      const result = await bridge.sendCommand("set_hotspot_state", {enabled: false})
      console.log("[GalleryScreen] Hotspot stop command sent")
      setGalleryOpenedHotspot(false)
      galleryOpenedHotspotRef.current = false
      return result
    } catch (error) {
      console.error("[GalleryScreen] Failed to stop hotspot:", error)
      throw error
    }
  }

  // Handle photo deletion
  const handleDeletePhoto = async (photo: PhotoInfo) => {
    const hasConnection = isHotspotEnabled && hotspotGatewayIp

    if (!hasConnection) {
      showAlert("Error", "Glasses not connected", [{text: translate("common:ok")}])
      return
    }

    showAlert("Delete Photo", `Are you sure you want to delete "${photo.name}"?`, [
      {text: translate("common:cancel"), style: "cancel"},
      {
        text: translate("common:delete"),
        style: "destructive",
        onPress: async () => {
          try {
            await asgCameraApi.deleteFilesFromServer([photo.name])
            showAlert("Success", "Photo deleted successfully!", [{text: translate("common:ok")}])
            loadInitialPhotos()
          } catch (err) {
            showAlert("Error", err instanceof Error ? err.message : "Failed to delete photo", [
              {text: translate("common:ok")},
            ])
          }
        },
      },
    ])
  }

  // Handle downloaded photo deletion
  const handleDeleteDownloadedPhoto = async (photo: PhotoInfo) => {
    showAlert("Delete Downloaded Photo", `Are you sure you want to delete "${photo.name}" from local storage?`, [
      {text: translate("common:cancel"), style: "cancel"},
      {
        text: translate("common:delete"),
        style: "destructive",
        onPress: async () => {
          try {
            await localStorageService.deleteDownloadedFile(photo.name)
            await loadDownloadedPhotos()
          } catch {
            showAlert("Error", "Failed to delete photo from local storage", [{text: translate("common:ok")}])
          }
        },
      },
    ])
  }

  // Handle delete all photos
  // const _handleDeleteAll = async () => {
  //   const totalServerPhotos = totalServerCount
  //   const totalLocalPhotos = downloadedPhotos.length
  //   const totalPhotos = totalServerPhotos + totalLocalPhotos

  //   if (totalPhotos === 0) {
  //     showAlert("No Photos", "There are no photos to delete", [{text: translate("common:ok")}])
  //     return
  //   }

  //   const itemText = totalPhotos === 1 ? "item" : "items"
  //   let message = `This will permanently delete all ${totalPhotos} ${itemText}`

  //   if (totalServerPhotos > 0 && totalLocalPhotos > 0) {
  //     message += ` (${totalServerPhotos} from glasses, ${totalLocalPhotos} from local storage)`
  //   } else if (totalServerPhotos > 0) {
  //     message += ` from your glasses`
  //   } else {
  //     message += ` from local storage`
  //   }

  //   message += ". This action cannot be undone."

  //   showAlert("Delete All Photos", message, [
  //     {text: translate("common:cancel"), style: "cancel"},
  //     {
  //       text: "Delete All",
  //       style: "destructive",
  //       onPress: async () => {
  //         try {
  //           let deleteErrors: string[] = []

  //           // Delete all server photos if connected
  //           if (totalServerPhotos > 0 && isHotspotEnabled && hotspotGatewayIp) {
  //             try {
  //               // Get all server photo names
  //               const serverPhotoNames: string[] = []
  //               for (let i = 0; i < totalServerCount; i++) {
  //                 const photo = loadedServerPhotos.get(i)
  //                 if (photo) {
  //                   serverPhotoNames.push(photo.name)
  //                 }
  //               }

  //               if (serverPhotoNames.length > 0) {
  //                 const deleteResult = await asgCameraApi.deleteFilesFromServer(serverPhotoNames)
  //                 if (deleteResult.failed.length > 0) {
  //                   deleteErrors.push(`Failed to delete ${deleteResult.failed.length} photos from glasses`)
  //                 }
  //                 console.log(`[GalleryScreen] Deleted ${deleteResult.deleted.length} photos from server`)
  //               }
  //             } catch (err) {
  //               console.error("Error deleting server photos:", err)
  //               deleteErrors.push("Failed to delete photos from glasses")
  //             }
  //           }

  //           // Delete all local photos
  //           if (totalLocalPhotos > 0) {
  //             try {
  //               await localStorageService.clearAllFiles()
  //               console.log(`[GalleryScreen] Cleared all local photos`)
  //             } catch (err) {
  //               console.error("Error deleting local photos:", err)
  //               deleteErrors.push("Failed to delete local photos")
  //             }
  //           }

  //           // Refresh the gallery
  //           setLoadedServerPhotos(new Map())
  //           setTotalServerCount(0)
  //           loadedRanges.current.clear()
  //           loadingRanges.current.clear()
  //           setPhotoSyncStates(new Map())
  //           await loadDownloadedPhotos()

  //           // Refresh server photos if connected
  //           if (isHotspotEnabled && hotspotGatewayIp) {
  //             loadInitialPhotos()
  //           }

  //           if (deleteErrors.length > 0) {
  //             showAlert("Partial Success", deleteErrors.join(". "), [{text: translate("common:ok")}])
  //           } else {
  //             showAlert("Success", "All photos deleted successfully!", [{text: translate("common:ok")}])
  //           }
  //         } catch {
  //           showAlert("Error", "Failed to delete photos", [{text: translate("common:ok")}])
  //         }
  //       },
  //     },
  //   ])
  // }

  // Connect to hotspot
  const connectToHotspot = async (ssid: string, password: string, ip: string) => {
    try {
      console.log(`[GalleryScreen] Connecting to ${ssid}...`)
      transitionToState(GalleryState.CONNECTING_TO_HOTSPOT)

      await WifiManager.connectToProtectedSSID(ssid, password, false, false)
      console.log("[GalleryScreen] Successfully connected to hotspot!")

      if (ip) {
        asgCameraApi.setServer(ip, 8089)
        transitionToState(GalleryState.CONNECTED_LOADING)

        try {
          const currentSSID = await WifiManager.getCurrentWifiSSID()
          console.log("[GalleryScreen] Phone's current SSID:", currentSSID)
        } catch (error) {
          console.log("[GalleryScreen] Failed to get current SSID:", error)
        }

        setTimeout(() => {
          loadInitialPhotos(ip)
        }, TIMING.HOTSPOT_LOAD_DELAY_MS)
      }
    } catch (error: any) {
      console.log("[GalleryScreen] Failed to connect:", error)

      if (
        error?.code === "userDenied" ||
        error?.code === "unableToConnect" ||
        error?.message?.includes("cancel") ||
        error?.message?.includes("approval")
      ) {
        console.log("[GalleryScreen] User cancelled WiFi connection")
        transitionToState(GalleryState.USER_CANCELLED_WIFI)
      } else if (error?.message?.includes("user has to enable wifi manually")) {
        // Android 10+ requires manual WiFi enable
        setErrorMessage("Please enable WiFi in your device settings first")
        showAlert("WiFi Required", "Please enable WiFi in your device settings and try again", [{text: "OK"}])
        transitionToState(GalleryState.USER_CANCELLED_WIFI)
      } else {
        setErrorMessage(error?.message || "Failed to connect to hotspot")
        transitionToState(GalleryState.ERROR)
      }
    }
  }

  // Retry hotspot connection
  const retryHotspotConnection = () => {
    if (!hotspotSsid || !hotspotPassword || !hotspotGatewayIp) {
      handleRequestHotspot()
      return
    }

    transitionToState(GalleryState.WAITING_FOR_WIFI_PROMPT)
    connectToHotspot(hotspotSsid, hotspotPassword, hotspotGatewayIp)
  }

  // Query gallery status
  const queryGlassesGalleryStatus = () => {
    console.log("[GalleryScreen] Querying glasses gallery status...")
    bridge
      .queryGalleryStatus()
      .catch(error => console.error("[GalleryScreen] Failed to send gallery status query:", error))
  }

  // Initial mount - check permission first
  useEffect(() => {
    const checkAndRequestPermission = async () => {
      console.log("[GalleryScreen] Component mounted - checking media library permission")
      const hasPermission = await MediaLibraryPermissions.checkPermission()

      if (!hasPermission) {
        setIsRequestingPermission(true)
        const granted = await MediaLibraryPermissions.requestPermission()
        setIsRequestingPermission(false)

        if (!granted) {
          // Show alert and navigate back
          showAlert(
            "Permission Required",
            "MentraOS needs permission to save photos to your camera roll. Please grant permission in Settings.",
            [
              {text: "Cancel", onPress: () => goBack()},
              {
                text: "Open Settings",
                onPress: () => {
                  Linking.openSettings()
                  goBack()
                },
              },
            ],
          )
          return
        }
      }

      setHasMediaLibraryPermission(true)
      console.log("[GalleryScreen] Media library permission granted")

      // Continue with existing mount logic
      loadDownloadedPhotos()

      // Only query glasses if we have glasses info (meaning glasses are connected) AND glasses have gallery capability
      if (status.glasses_info?.model_name && hasGallery(status.glasses_info.model_name)) {
        console.log(
          "[GalleryScreen] Glasses connected with gallery capability - querying gallery status",
          status.glasses_info,
        )
        transitionToState(GalleryState.QUERYING_GLASSES)
        queryGlassesGalleryStatus()
      } else {
        console.log(
          "[GalleryScreen] No glasses connected or glasses don't have gallery capability - showing local photos only",
        )
        transitionToState(GalleryState.NO_MEDIA_ON_GLASSES)
      }
    }

    checkAndRequestPermission()
  }, [])

  // Refresh downloaded photos when screen comes into focus
  useFocusEffect(
    useCallback(() => {
      console.log("[GalleryScreen] Screen focused - refreshing downloaded photos")
      loadDownloadedPhotos()
    }, []),
  )

  // Handle back button
  useFocusEffect(
    useCallback(() => {
      const onBackPress = () => {
        if (!selectedPhoto) return false
        setSelectedPhoto(null)
        return true
      }

      BackHandler.addEventListener("hardwareBackPress", onBackPress)
      return () => BackHandler.removeEventListener("hardwareBackPress", onBackPress)
    }, [selectedPhoto]),
  )

  // Listen for gallery status
  useEffect(() => {
    const handleGalleryStatus = (data: any) => {
      console.log("[GalleryScreen] Received GLASSES_GALLERY_STATUS event:", data)

      setGlassesGalleryStatus({
        photos: data.photos || 0,
        videos: data.videos || 0,
        total: data.total || 0,
        has_content: data.has_content || false,
      })

      if (!data.has_content) {
        console.log("[GalleryScreen] No content on glasses")
        setTotalServerCount(0)
        transitionToState(GalleryState.NO_MEDIA_ON_GLASSES)
        return
      }

      if (data.camera_busy) {
        const busyMessage =
          data.camera_busy === "stream"
            ? "streaming"
            : data.camera_busy === "video"
              ? "recording video"
              : "using the camera"
        const itemText = data.total === 1 ? "item" : "items"

        showAlert(
          "Camera Busy",
          `Cannot fetch ${data.total || 0} ${itemText} from glasses while ${busyMessage}. Please stop ${busyMessage} first to sync.`,
          [{text: "OK"}],
          {iconName: "camera", iconColor: "#FF9800"},
        )

        setTotalServerCount(0)
        transitionToState(GalleryState.NO_MEDIA_ON_GLASSES)
        return
      }

      const phoneConnectedToHotspot = networkStatus.phoneSSID && hotspotSsid && networkStatus.phoneSSID === hotspotSsid

      if (phoneConnectedToHotspot) {
        console.log("[GalleryScreen] Already connected to hotspot")
        transitionToState(GalleryState.CONNECTED_LOADING)
        loadInitialPhotos(hotspotGatewayIp)
        return
      }

      // Handle different gallery states - transition to MEDIA_AVAILABLE unless already connecting/syncing
      const canTransitionToMediaAvailable = ![
        GalleryState.REQUESTING_HOTSPOT,
        GalleryState.WAITING_FOR_WIFI_PROMPT,
        GalleryState.CONNECTING_TO_HOTSPOT,
        GalleryState.CONNECTED_LOADING,
        GalleryState.READY_TO_SYNC,
        GalleryState.SYNCING,
      ].includes(galleryState)

      if (canTransitionToMediaAvailable) {
        if (galleryState === GalleryState.QUERYING_GLASSES) {
          console.log("21 [GalleryScreen] Media available, requesting hotspot")
        } else {
          console.log("[GalleryScreen] 📸 Gallery status update (state: " + galleryState + "), showing sync option")
        }
        transitionToState(GalleryState.MEDIA_AVAILABLE)
      }
    }

    GlobalEventEmitter.addListener("GLASSES_GALLERY_STATUS", handleGalleryStatus)
    return () => {
      GlobalEventEmitter.removeListener("GLASSES_GALLERY_STATUS", handleGalleryStatus)
    }
  }, [galleryState, networkStatus.phoneSSID, hotspotSsid])

  // MEDIA_AVAILABLE state shows the sync button - user can manually tap to initiate connection
  // No auto-request behavior needed

  // Listen for hotspot ready
  useEffect(() => {
    const handleHotspotStatusChange = (eventData: any) => {
      console.log("[GalleryScreen] Received GLASSES_HOTSPOT_STATUS_CHANGE event:", eventData)

      if (!eventData.enabled || !eventData.ssid || !eventData.password || !galleryOpenedHotspotRef.current) {
        return
      }

      console.log("[GalleryScreen] Hotspot enabled, attempting to connect...")
      connectToHotspot(eventData.ssid, eventData.password, eventData.local_ip)
    }

    GlobalEventEmitter.addListener("GLASSES_HOTSPOT_STATUS_CHANGE", handleHotspotStatusChange)
    return () => {
      GlobalEventEmitter.removeListener("GLASSES_HOTSPOT_STATUS_CHANGE", handleHotspotStatusChange)
    }
  }, [])

  // Monitor phone SSID
  useEffect(() => {
    const phoneSSID = networkStatus.phoneSSID

    if (!phoneSSID || !hotspotSsid || phoneSSID !== hotspotSsid || !hotspotGatewayIp) return

    console.log("[GalleryScreen] Phone connected to glasses hotspot!")
    transitionToState(GalleryState.CONNECTED_LOADING)
    asgCameraApi.setServer(hotspotGatewayIp, 8089)

    setTimeout(() => {
      console.log("[GalleryScreen] Loading photos via hotspot...")
      loadInitialPhotos(hotspotGatewayIp)
    }, 500)
  }, [networkStatus.phoneSSID, hotspotSsid, hotspotGatewayIp])

  // Auto-trigger sync
  useEffect(() => {
    if (galleryState !== GalleryState.READY_TO_SYNC || totalServerCount === 0 || syncTriggeredRef.current) {
      return
    }

    console.log("[GalleryScreen] Ready to sync, auto-starting...")
    syncTriggeredRef.current = true
    setTimeout(() => {
      handleSync().finally(() => {
        syncTriggeredRef.current = false
      })
    }, 500)
  }, [galleryState, totalServerCount])

  // Note: Hotspot cleanup after sync is now handled directly in syncAllPhotos()
  // instead of using useEffect to watch for SYNC_COMPLETE state, which was unreliable
  // due to immediate state transitions

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (!galleryOpenedHotspot) return

      console.log("[GalleryScreen] Gallery unmounting - closing hotspot")
      bridge
        .sendCommand("set_hotspot_state", {enabled: false})
        .then(() => console.log("[GalleryScreen] Closed hotspot on exit"))
        .catch(error => console.error("[GalleryScreen] Failed to close hotspot on exit:", error))
    }
  }, [galleryOpenedHotspot])

  // Combine photos with placeholders
  const allPhotos = useMemo(() => {
    const items: GalleryItem[] = []

    // Server photos
    for (let i = 0; i < totalServerCount; i++) {
      const photo = loadedServerPhotos.get(i)
      items.push({
        id: `server-${i}`,
        type: photo ? "server" : "placeholder",
        index: i,
        photo,
        isOnServer: true,
      })
    }

    // Downloaded-only photos
    const serverPhotoNames = new Set<string>()
    loadedServerPhotos.forEach(photo => serverPhotoNames.add(photo.name))

    const downloadedOnly = downloadedPhotos
      .filter(p => !serverPhotoNames.has(p.name))
      .sort((a, b) => {
        const aTime = typeof a.modified === "string" ? new Date(a.modified).getTime() : a.modified
        const bTime = typeof b.modified === "string" ? new Date(b.modified).getTime() : b.modified
        return bTime - aTime
      })

    downloadedOnly.forEach((photo, i) => {
      items.push({
        id: `local-${photo.name}`,
        type: "local",
        index: totalServerCount + i,
        photo,
        isOnServer: false,
      })
    })

    return items
  }, [totalServerCount, loadedServerPhotos, downloadedPhotos])

  // Viewability tracking
  const onViewableItemsChanged = useRef(({viewableItems}: {viewableItems: ViewToken[]}) => {
    const placeholderIndices = viewableItems
      .filter(item => item.item?.type === "placeholder")
      .map(item => item.item.index)

    if (placeholderIndices.length === 0) return

    const minIndex = Math.max(0, Math.min(...placeholderIndices) - 5)
    const maxIndex = Math.min(totalServerCount - 1, Math.max(...placeholderIndices) + 5)

    const indicesToLoad = []
    for (let i = minIndex; i <= maxIndex; i++) {
      indicesToLoad.push(i)
    }

    loadPhotosForIndices(indicesToLoad)
  }).current

  const viewabilityConfig = useRef({
    itemVisiblePercentThreshold: 10,
    minimumViewTime: 100,
  }).current

  // UI state
  const isLoadingServerPhotos = [
    GalleryState.CONNECTED_LOADING,
    GalleryState.INITIALIZING,
    GalleryState.QUERYING_GLASSES,
  ].includes(galleryState)

  const error = galleryState === GalleryState.ERROR ? errorMessage : null
  const serverPhotosToSync = totalServerCount

  const shouldShowSyncButton =
    [
      GalleryState.MEDIA_AVAILABLE,
      GalleryState.CONNECTED_LOADING,
      GalleryState.USER_CANCELLED_WIFI,
      GalleryState.WAITING_FOR_WIFI_PROMPT,
      GalleryState.CONNECTING_TO_HOTSPOT,
      GalleryState.REQUESTING_HOTSPOT,
      GalleryState.SYNCING,
      GalleryState.SYNC_COMPLETE,
      GalleryState.ERROR,
    ].includes(galleryState) ||
    (galleryState === GalleryState.READY_TO_SYNC && serverPhotosToSync > 0)

  const renderStatusBar = () => {
    if (!shouldShowSyncButton) return null

    const statusContent = () => {
      console.log("[GalleryScreen] Rendering status content for state:", galleryState)
      switch (galleryState) {
        case GalleryState.MEDIA_AVAILABLE:
          return (
            <View>
              <View style={themed($syncButtonRow)}>
                <MaterialCommunityIcons
                  name="download-circle-outline"
                  size={20}
                  color={theme.colors.text}
                  style={{marginRight: spacing.xs}}
                />
                <Text style={themed($syncButtonText)}>
                  Sync {glassesGalleryStatus?.total || 0}{" "}
                  {(glassesGalleryStatus?.photos || 0) > 0 && (glassesGalleryStatus?.videos || 0) > 0
                    ? (glassesGalleryStatus?.total || 0) === 1
                      ? "item"
                      : "items"
                    : (glassesGalleryStatus?.photos || 0) > 0
                      ? (glassesGalleryStatus?.photos || 0) === 1
                        ? "photo"
                        : "photos"
                      : (glassesGalleryStatus?.videos || 0) === 1
                        ? "video"
                        : "videos"}
                </Text>
              </View>
            </View>
          )

        case GalleryState.REQUESTING_HOTSPOT:
          return (
            <View style={themed($syncButtonRow)}>
              <ActivityIndicator size="small" color={theme.colors.text} style={{marginRight: spacing.xs}} />
              <Text style={themed($syncButtonText)}>Starting hotspot...</Text>
            </View>
          )

        case GalleryState.WAITING_FOR_WIFI_PROMPT:
          return (
            <View style={themed($syncButtonRow)}>
              <ActivityIndicator size="small" color={theme.colors.text} style={{marginRight: spacing.xs}} />
              <Text style={themed($syncButtonText)}>Waiting for connection...</Text>
            </View>
          )

        case GalleryState.CONNECTING_TO_HOTSPOT:
          return (
            <View style={themed($syncButtonRow)}>
              <ActivityIndicator size="small" color={theme.colors.text} style={{marginRight: spacing.xs}} />
              <Text style={themed($syncButtonText)}>Connecting to hotspot...</Text>
            </View>
          )

        case GalleryState.CONNECTED_LOADING:
          return (
            <View style={themed($syncButtonRow)}>
              <ActivityIndicator size="small" color={theme.colors.text} style={{marginRight: spacing.xs}} />
              <Text style={themed($syncButtonText)}>Loading photos...</Text>
            </View>
          )

        case GalleryState.USER_CANCELLED_WIFI:
          // if (!hotspotSsid || !glassesGalleryStatus?.has_content) return null
          return (
            <View>
              <View style={themed($syncButtonRow)}>
                <MaterialCommunityIcons
                  name="wifi-alert"
                  size={20}
                  color={theme.colors.text}
                  style={{marginRight: spacing.xs}}
                />
                <Text style={themed($syncButtonText)}>
                  Sync {glassesGalleryStatus?.total || 0}{" "}
                  {(glassesGalleryStatus?.photos || 0) > 0 && (glassesGalleryStatus?.videos || 0) > 0
                    ? (glassesGalleryStatus?.total || 0) === 1
                      ? "item"
                      : "items"
                    : (glassesGalleryStatus?.photos || 0) > 0
                      ? (glassesGalleryStatus?.photos || 0) === 1
                        ? "photo"
                        : "photos"
                      : (glassesGalleryStatus?.videos || 0) === 1
                        ? "video"
                        : "videos"}
                </Text>
              </View>
            </View>
          )

        case GalleryState.READY_TO_SYNC:
          if (serverPhotosToSync === 0) return null
          return (
            <View style={themed($syncButtonRow)}>
              <Text style={themed($syncButtonText)}>Syncing {serverPhotosToSync} items...</Text>
            </View>
          )

        case GalleryState.SYNCING:
          if (!syncProgress) {
            return (
              <View style={themed($syncButtonRow)}>
                <ActivityIndicator size="small" color={theme.colors.text} style={{marginRight: spacing.xs}} />
                <Text style={themed($syncButtonText)}>Syncing {serverPhotosToSync} items...</Text>
              </View>
            )
          }
          return (
            <>
              <Text style={themed($syncButtonText)}>
                Syncing {syncProgress.current} of {syncProgress.total} items
              </Text>
              <View style={themed($syncButtonProgressBar)}>
                <View
                  style={[
                    themed($syncButtonProgressFill),
                    {
                      width: `${Math.round((syncProgress.current / syncProgress.total) * 100)}%`,
                    },
                  ]}
                />
              </View>
            </>
          )

        case GalleryState.SYNC_COMPLETE:
          return (
            <View style={themed($syncButtonRow)}>
              <Text style={themed($syncButtonText)}>Sync complete!</Text>
            </View>
          )

        case GalleryState.ERROR:
          return (
            <View style={themed($syncButtonRow)}>
              <Text style={themed($syncButtonText)}>{errorMessage || "An error occurred"}</Text>
            </View>
          )

        default:
          return null
      }
    }

    const isTappable =
      galleryState === GalleryState.MEDIA_AVAILABLE || galleryState === GalleryState.USER_CANCELLED_WIFI

    return (
      <TouchableOpacity
        style={[themed($syncButtonFixed)]}
        onPress={isTappable ? retryHotspotConnection : undefined}
        activeOpacity={isTappable ? 0.8 : 1}
        disabled={!isTappable}>
        <View style={themed($syncButtonContent)}>{statusContent()}</View>
      </TouchableOpacity>
    )
  }

  const renderPhotoItem = ({item}: {item: GalleryItem}) => {
    if (!item.photo) {
      return (
        <View style={[themed($photoItem), {width: itemWidth}]}>
          <ShimmerPlaceholder
            shimmerColors={[theme.colors.border, theme.colors.background, theme.colors.border]}
            shimmerStyle={{
              width: itemWidth,
              height: itemWidth, // Square aspect ratio like Google/Apple Photos
              borderRadius: 0,
            }}
            duration={1500}
          />
        </View>
      )
    }

    const syncState = photoSyncStates.get(item.photo.name)
    const isDownloading =
      syncState &&
      (syncState.status === "downloading" || syncState.status === "pending" || syncState.status === "completed")

    return (
      <TouchableOpacity
        style={[themed($photoItem), {width: itemWidth}, isDownloading && themed($photoItemDisabled)]}
        onPress={() => handlePhotoPress(item)}
        onLongPress={() => {
          if (item.photo) {
            if (item.isOnServer) {
              handleDeletePhoto(item.photo)
            } else {
              handleDeleteDownloadedPhoto(item.photo)
            }
          }
        }}
        disabled={isDownloading}
        activeOpacity={isDownloading ? 1 : 0.8}>
        <View style={{position: "relative"}}>
          <PhotoImage photo={item.photo} style={{...themed($photoImage), width: itemWidth, height: itemWidth}} />
          {isDownloading && <View style={themed($photoDimmingOverlay)} />}
        </View>
        {item.isOnServer && (
          <View style={themed($serverBadge)}>
            <MaterialCommunityIcons name="glasses" size={14} color="white" />
          </View>
        )}
        {item.photo.is_video && (
          <View style={themed($videoIndicator)}>
            <MaterialCommunityIcons name="video" size={14} color="white" />
          </View>
        )}
        {(() => {
          const syncState = photoSyncStates.get(item.photo.name)
          if (syncState) {
            console.log(`[GalleryScreen] Rendering progress for ${item.photo.name}:`, syncState)
          }
          if (
            syncState &&
            (syncState.status === "pending" ||
              syncState.status === "downloading" ||
              syncState.status === "failed" ||
              syncState.status === "completed")
          ) {
            const isFailed = syncState.status === "failed"
            const isCompleted = syncState.status === "completed"

            return (
              <View style={themed($progressRingOverlay)}>
                <ProgressRing
                  progress={Math.max(0, Math.min(100, syncState.progress || 0))}
                  size={50}
                  strokeWidth={4}
                  showPercentage={!isFailed && !isCompleted}
                  progressColor={isFailed ? theme.colors.error : isCompleted ? theme.colors.tint : theme.colors.tint}
                />
                {isFailed && (
                  <View
                    style={{
                      position: "absolute",
                      justifyContent: "center",
                      alignItems: "center",
                      width: 50,
                      height: 50,
                    }}>
                    <MaterialCommunityIcons name="alert-circle" size={20} color={theme.colors.error} />
                  </View>
                )}
                {isCompleted && (
                  <View
                    style={{
                      position: "absolute",
                      justifyContent: "center",
                      alignItems: "center",
                      width: 50,
                      height: 50,
                    }}>
                    <MaterialCommunityIcons name="check-circle" size={20} color={theme.colors.tint} />
                  </View>
                )}
              </View>
            )
          }
          return null
        })()}
      </TouchableOpacity>
    )
  }

  // Show permission loading state
  if (isRequestingPermission || !hasMediaLibraryPermission) {
    return (
      <>
        <Header title="Glasses Gallery" leftIcon="caretLeft" onLeftPress={() => goBack()} />
        <View style={themed($screenContainer)}>
          <View style={themed($permissionContainer)}>
            <ActivityIndicator size="large" color={theme.colors.tint} />
            <Text style={themed($permissionText)}>
              {isRequestingPermission ? "Requesting photo library permission..." : "Loading gallery..."}
            </Text>
          </View>
        </View>
      </>
    )
  }

  return (
    <>
      <Header
        title="Glasses Gallery"
        leftIcon="caretLeft"
        onLeftPress={() => goBack()}
        RightActionComponent={
          <TouchableOpacity onPress={() => push("/asg/gallery-settings")} style={themed($settingsButton)}>
            <MaterialCommunityIcons name="cog" size={24} color={theme.colors.text} />
          </TouchableOpacity>
        }
      />
      <View style={themed($screenContainer)}>
        <View style={themed($galleryContainer)}>
          {(() => {
            // DEBUG: Log empty gallery condition evaluation
            const showEmpty = allPhotos.length === 0 && !isLoadingServerPhotos
            // console.log(`[GalleryScreen] GALLERY STATE: ${galleryState}`)
            // console.log(`[GalleryScreen] EMPTY GALLERY CHECK:`, {
            //   hasError: !!error,
            //   allPhotosLength: allPhotos.length,
            //   isLoadingServerPhotos,
            //   galleryState,
            //   totalServerCount,
            //   downloadedPhotosCount: downloadedPhotos.length,
            //   showEmptyGallery: showEmpty && !error,
            //   decision: error ? "SHOW_ERROR" : showEmpty ? "SHOW_EMPTY" : "SHOW_GALLERY",
            // })

            if (error) {
              return (
                <View style={themed($errorContainer)}>
                  <Text style={themed($errorText)}>{error}</Text>
                </View>
              )
            } else if (showEmpty) {
              return (
                <View style={themed($emptyContainer)}>
                  <MaterialCommunityIcons
                    name="image-outline"
                    size={64}
                    color={theme.colors.textDim}
                    style={{marginBottom: spacing.lg}}
                  />
                  <Text style={themed($emptyText)}>Gallery is empty</Text>
                  <Text style={themed($emptySubtext)}>
                    Take photos with your glasses or sync existing photos to see them here.
                  </Text>
                </View>
              )
            } else {
              return (
                <FlatList
                  data={allPhotos}
                  numColumns={numColumns}
                  key={numColumns}
                  renderItem={renderPhotoItem}
                  keyExtractor={item => item.id}
                  contentContainerStyle={[
                    themed($photoGridContent),
                    {paddingBottom: shouldShowSyncButton ? 100 : spacing.lg},
                  ]}
                  columnWrapperStyle={numColumns > 1 ? themed($columnWrapper) : undefined}
                  ItemSeparatorComponent={() => <View style={{height: ITEM_SPACING}} />}
                  initialNumToRender={10}
                  maxToRenderPerBatch={10}
                  windowSize={10}
                  removeClippedSubviews={true}
                  updateCellsBatchingPeriod={50}
                  onViewableItemsChanged={onViewableItemsChanged}
                  viewabilityConfig={viewabilityConfig}
                />
              )
            }
          })()}
        </View>

        {renderStatusBar()}

        <MediaViewer
          visible={!!selectedPhoto}
          photo={selectedPhoto}
          onClose={() => setSelectedPhoto(null)}
          onShare={() => selectedPhoto && handleSharePhoto(selectedPhoto)}
        />
      </View>
    </>
  )
}

// Styles remain the same
const $screenContainer: ThemedStyle<ViewStyle> = ({spacing}) => ({
  flex: 1,
  // backgroundColor: colors.background,
  marginHorizontal: -spacing.lg,
})

const $errorContainer: ThemedStyle<ViewStyle> = ({colors, spacing}) => ({
  backgroundColor: colors.palette.angry100,
  padding: spacing.sm,
  borderRadius: spacing.xs,
  margin: spacing.lg,
  alignItems: "center",
})

const $errorText: ThemedStyle<TextStyle> = ({colors, spacing}) => ({
  fontSize: 14,
  color: colors.palette.angry500,
  textAlign: "center",
  marginBottom: spacing.sm,
})

const $photoGridContent: ThemedStyle<ViewStyle> = () => ({
  paddingHorizontal: 0, // No horizontal padding for edge-to-edge layout
  paddingTop: 0, // No top padding for edge-to-edge layout
})

const $columnWrapper: ThemedStyle<ViewStyle> = () => ({
  justifyContent: "flex-start",
  gap: 2, // Minimal spacing between columns
})

const $emptyContainer: ThemedStyle<ViewStyle> = ({spacing}) => ({
  flex: 1,
  justifyContent: "center",
  alignItems: "center",
  padding: spacing.xl,
})

const $emptyText: ThemedStyle<TextStyle> = ({colors, spacing}) => ({
  fontSize: 18,
  color: colors.text,
  marginBottom: spacing.xs,
})

const $emptySubtext: ThemedStyle<TextStyle> = ({colors, spacing}) => ({
  fontSize: 14,
  color: colors.textDim,
  textAlign: "center",
  lineHeight: 20,
  paddingHorizontal: spacing.lg,
})

const $photoItem: ThemedStyle<ViewStyle> = () => ({
  borderRadius: 0, // No rounding like Google Photos / Apple Photos
  overflow: "hidden",
  backgroundColor: "rgba(0,0,0,0.05)",
})

const $photoImage: ThemedStyle<ImageStyle> = () => ({
  width: "100%",
  borderRadius: 0, // No rounding like Google Photos / Apple Photos
})

const $videoIndicator: ThemedStyle<ViewStyle> = ({spacing}) => ({
  position: "absolute",
  top: spacing.xs,
  left: spacing.xs,
  backgroundColor: "rgba(0,0,0,0.7)",
  borderRadius: 12,
  paddingHorizontal: 6,
  paddingVertical: 3,
  shadowColor: "#000",
  shadowOffset: {width: 0, height: 1},
  shadowOpacity: 0.3,
  shadowRadius: 2,
  elevation: 3,
})

const $progressRingOverlay: ThemedStyle<ViewStyle> = () => ({
  position: "absolute",
  top: 0,
  left: 0,
  right: 0,
  bottom: 0,
  justifyContent: "center",
  alignItems: "center",
  borderRadius: 0,
})

const $galleryContainer: ThemedStyle<ViewStyle> = () => ({
  flex: 1,
})

const $syncButtonFixed: ThemedStyle<ViewStyle> = ({colors, spacing, isDark}) => ({
  position: "absolute",
  bottom: spacing.xl,
  left: spacing.lg,
  right: spacing.lg,
  backgroundColor: colors.background,
  color: colors.text,
  borderRadius: spacing.md,
  borderWidth: spacing.xxxs,
  borderColor: colors.border,
  paddingVertical: spacing.md,
  paddingHorizontal: spacing.lg,
  ...(isDark
    ? {}
    : {
        shadowColor: "#000",
        shadowOffset: {width: 0, height: 2},
        shadowOpacity: 0.15,
        shadowRadius: 3.84,
        elevation: 5,
      }),
})

const $syncButtonContent: ThemedStyle<ViewStyle> = () => ({
  alignItems: "center",
  justifyContent: "center",
})

const $syncButtonRow: ThemedStyle<ViewStyle> = () => ({
  flexDirection: "row",
  alignItems: "center",
  justifyContent: "center",
})

const $syncButtonText: ThemedStyle<TextStyle> = ({colors}) => ({
  fontSize: 16,
  fontWeight: "600",
  color: colors.text,
  // marginBottom: 2,
})

const $serverBadge: ThemedStyle<ViewStyle> = ({spacing}) => ({
  position: "absolute",
  top: spacing.xs,
  right: spacing.xs,
  backgroundColor: "rgba(0,0,0,0.7)",
  borderRadius: 12,
  paddingHorizontal: 6,
  paddingVertical: 3,
  justifyContent: "center",
  alignItems: "center",
  shadowColor: "#000",
  shadowOffset: {width: 0, height: 1},
  shadowOpacity: 0.3,
  shadowRadius: 2,
  elevation: 3,
})

// const _$deleteAllButton: ThemedStyle<ViewStyle> = ({spacing}) => ({
//   paddingHorizontal: spacing.sm,
//   paddingVertical: spacing.xs,
//   borderRadius: spacing.sm,
//   justifyContent: "center",
//   alignItems: "center",
//   minWidth: 44,
//   minHeight: 44,
// })

const $syncButtonProgressBar: ThemedStyle<ViewStyle> = ({colors, spacing}) => ({
  height: 6,
  backgroundColor: colors.border,
  borderRadius: 3,
  overflow: "hidden",
  marginTop: spacing.xs,
  width: "100%",
})

const $syncButtonProgressFill: ThemedStyle<ViewStyle> = ({colors}) => ({
  height: "100%",
  backgroundColor: colors.palette.primary500,
  borderRadius: 2,
})

const $photoDimmingOverlay: ThemedStyle<ViewStyle> = () => ({
  position: "absolute",
  top: 0,
  left: 0,
  right: 0,
  bottom: 0,
  backgroundColor: "rgba(0,0,0,0.5)",
  borderRadius: 0,
})

const $photoItemDisabled: ThemedStyle<ViewStyle> = () => ({
  // Removed opacity to prevent greyed out appearance during sync
})

const $permissionContainer: ThemedStyle<ViewStyle> = ({spacing}) => ({
  flex: 1,
  justifyContent: "center",
  alignItems: "center",
  padding: spacing.xl,
})

const $permissionText: ThemedStyle<TextStyle> = ({colors, spacing}) => ({
  fontSize: 16,
  color: colors.textDim,
  marginTop: spacing.md,
  textAlign: "center",
})

const $settingsButton: ThemedStyle<ViewStyle> = ({spacing}) => ({
  paddingHorizontal: spacing.sm,
  paddingVertical: spacing.xs,
  borderRadius: spacing.sm,
  justifyContent: "center",
  alignItems: "center",
  minWidth: 44,
  minHeight: 44,
})
