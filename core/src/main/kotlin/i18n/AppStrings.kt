package com.comics8.core.i18n

interface AppStrings {
    // 1. Navigation & Tabs
    val tabFavorite: String
    val catalogLibrary: String
    val catalogLatest: String
    val navHistory: String
    val navStorage: String
    fun navStorageNamed(name: String): String
    val navSettings: String
    val navSourceManager: String
    val navDownloads: String
    val navReader: String
    val actionTableOfContents: String
    val navBackToList: String
    val navBackToLibrary: String
    val navBackToEpisodeList: String

    // 2. Common & Actions
    val actionBack: String
    val actionGoBack: String
    val actionRefresh: String
    val actionRetry: String
    val actionConfirm: String
    val actionCancel: String
    val actionClose: String
    val actionDelete: String
    val actionDisconnect: String
    val actionAdd: String
    val actionSave: String
    val msgSaveFailed: String
    val actionCopy: String
    val actionCopyCode: String
    val labelSelected: String
    val labelFixed: String
    val actionReorder: String
    val actionGoToSettings: String
    val actionPrev: String
    val actionNext: String
    val actionJump: String
    val actionSelectAll: String
    val actionDeselectAll: String
    val actionEdit: String
    val actionRemove: String

    // 3. Browse & Library
    val placeholderSearchTitle: String
    val placeholderSearchFileName: String
    val actionSearch: String
    val actionSearchClose: String
    val actionSearchExecute: String
    val actionSearchClear: String
    fun searchResultCount(count: Int): String
    fun searchResultEmpty(query: String): String
    val emptyFavorites: String
    val navToLatest: String
    val promptAddMangaFolder: String
    val promptAddFolder: String
    val actionAddFolder: String
    val titlePickMangaFolder: String
    val hintLibraryScan: String
    fun registeredFolderCount(count: Int): String
    val noRegisteredFolders: String
    fun connectedFolderCount(count: Int): String
    val hintNoLocalFolders: String
    val sectionConnectedLocalFolders: String
    val titleDisconnectFolder: String
    fun confirmDisconnectFolder(folder: String): String

    // 4. Series & Detail
    val errorNoSeriesInfo: String
    val errorFailedToLoadEpisodes: String
    fun actionResumeSeriesWithProgress(progress: String): String
    val actionResume: String
    val actionStartFromBeginning: String
    val actionAddFavorite: String
    val actionRemoveFavorite: String
    val badgeRead: String
    val actionOtherArtists: String
    val titlePickArtist: String
    val actionDownloadThisEpisode: String
    val labelDownloaded: String
    val progressModeLatestEpisode: String
    val progressModeLatestEpisodeDesc: String
    val progressModeReadCount: String
    val progressModeReadCountDesc: String
    val progressModePercentage: String
    val progressModePercentageDesc: String
    val progressModeHidden: String
    val progressModeHiddenDesc: String
    fun formatEpisodeNumber(order: Int): String
    fun formatItemCount(count: Int): String
    fun episodeItemCount(count: Int): String
    val sortOldestFirst: String
    val sortNewestFirst: String
    val sortDefault: String
    val sortNameAsc: String
    val sortNameDesc: String
    val sortDateDesc: String
    val sortDateAsc: String
    val labelEpisodeSortOrder: String
    val actionSortEpisodes: String

    // 5. Reader & Viewer
    val viewModeScroll: String
    val viewModeScrollShort: String
    val viewModeScrollDesc: String
    val viewModeSingle: String
    val viewModeSingleLong: String
    val viewModeSingleShort: String
    val viewModeSingleDesc: String
    val viewModeDual: String
    val viewModeDualLong: String
    val viewModeDualShort: String
    val viewModeDualDesc: String
    val labelDefaultViewMode: String
    val labelReadDirection: String
    val readDirectionRightToLeft: String
    val readDirectionRTLShort: String
    val readDirectionRTLDesc: String
    val readDirectionLeftToRight: String
    val readDirectionLTRShort: String
    val readDirectionLTRDesc: String
    val titleQuickSettings: String
    val labelPageTapZone: String
    val descPageTapZone: String
    val pageTapZoneDirection: String
    val descPageTapZoneDirection: String
    val pageTapZoneRightNext: String
    val descPageTapZoneRightNext: String
    val pageTapZoneLeftNext: String
    val descPageTapZoneLeftNext: String
    val labelVolumePageTurn: String
    val descVolumePageTurn: String
    val actionPrevEpisode: String
    val actionNextEpisode: String
    val actionEpisodeList: String
    fun labelPageNumber(page: Int): String
    val titlePageJump: String
    fun titlePageJumpRange(maxPage: Int): String
    val labelJumpTargetPage: String
    val promptLastPage: String
    val promptLastEpisode: String
    val promptNextEpisodeHint: String
    val promptCloseReaderHint: String
    val promptFirstPage: String
    val promptFirstEpisode: String
    val promptPrevEpisodeHint: String
    val errorImageLoadFailed: String
    val errorCannotLoadImage: String
    val errorNoImagesToLoad: String
    val errorFailedToLoadImages: String

    // 6. Downloads & History
    val titleOfflineDownload: String
    val downloadOptionAll: String
    fun downloadTotalEpisodes(count: Int): String
    val downloadOptionUnread: String
    fun downloadUnreadCount(count: Int): String
    val downloadOptionAfterOrder: String
    val downloadOptionAfterOrderDesc: String
    val labelDownloadStartOrder: String
    fun labelDownloadStartOrderRange(max: Int): String
    val noticeSequentialDownload: String
    val actionStartDownload: String
    val loadingAllEpisodes: String
    val loadingSimple: String
    fun downloadInProgressNamed(title: String): String
    val downloadInProgress: String
    fun downloadProgressImages(current: Int, total: Int, remainingEpisodes: Int): String
    fun downloadProgressPreparing(remainingEpisodes: Int): String
    val emptyDownloads: String
    fun badgeDownloadedEpisodeCount(count: Int): String
    val actionViewEpisodes: String
    val emptyHistory: String
    val actionClearAllHistory: String
    val timeJustNow: String
    fun timeMinutesAgo(minutes: Long): String
    fun timeHoursAgo(hours: Long): String
    fun timeDaysAgo(days: Long): String

    // 7. Source & Network
    val titleAddSource: String
    val actionAddSource: String
    val promptSelectSourceType: String
    val categoryFileStorage: String
    val sourceLocalFolder: String
    val sourceLocalFolderDesc: String
    val sourceSmb: String
    val sourceSmbDesc: String
    val sourceWebDav: String
    val sourceWebDavDesc: String
    val categoryOnlineExtensions: String
    val sourceJsExtension: String
    val sourceJsExtensionDesc: String
    val actionImportJsFile: String
    val sectionStorageSources: String
    val sectionOnlineSources: String
    val emptyOnlineSources: String
    val actionAddJsSource: String
    val labelSourceLocal: String
    val labelSourceSmb: String
    val labelSourceWebDav: String
    val labelSourceJs: String
    val labelSourceWeb: String
    fun subtitleSmbPath(host: String, share: String): String
    val subtitleSmbDefault: String
    fun subtitleWebDavUrl(url: String): String
    val subtitleWebDavDefault: String
    val promptSelectSource: String
    fun titleSourceSettings(name: String): String
    val sectionSourceInfoAndProgress: String
    val labelProgressDisplayMode: String
    val titleAddSmb: String
    val titleAddWebDav: String
    val titleSmbDetailSettings: String
    val titleWebDavDetailSettings: String
    val labelName: String
    val labelDisplayName: String
    val defaultSmbName: String
    val defaultWebDavName: String
    val labelServerAddress: String
    val labelServerIpHost: String
    val labelPort: String
    val labelShareName: String
    val labelShareNameWithAlias: String
    val labelFolderPathOptional: String
    val labelStartPathOptional: String
    val placeholderBooksExample: String
    val placeholderComicsExample: String
    val placeholderServerAddressExample: String
    val placeholderShareExample: String
    val labelDomainOptional: String
    val labelDomainWorkgroupOptional: String
    val labelWebDavUrl: String
    val labelWebDavServerUrl: String
    val labelUsernameOptional: String
    val labelUsernameShortOptional: String
    val labelPasswordOptional: String
    val labelPasswordFullOptional: String
    val statusCheckingConnection: String
    val actionRegister: String
    val actionTestConnection: String
    val actionSaveSmbConfig: String
    val actionSaveWebDavConfig: String
    val titleEditJsScript: String
    val actionSaveJsScript: String
    val sectionSourceManagementAndDelete: String
    val titleDeleteSource: String
    val descDeleteSource: String
    fun confirmDeleteSource(name: String): String
    val titleCannotImportSource: String

    // 8. Sync & Backup
    val sectionCloudSyncAndPairing: String
    val titleCloudSync: String
    val descCloudSync: String
    fun labelLastSyncedAt(time: String): String
    val labelNoSyncHistory: String
    val statusSyncInProgress: String
    val statusCloudConnected: String
    val statusSyncing: String
    val actionSyncNow: String
    val actionOpenPairingDialog: String
    val actionOpenConnectDialog: String
    val actionOpenConnectDialogShort: String
    val labelAutoSync: String
    val descAutoSync: String
    val labelServerProxy: String
    val descServerProxy: String

    // Network Settings
    val sectionNetwork: String
    val labelProxyMode: String
    val labelProxyDirect: String
    val descProxyDirect: String
    val labelProxyServer: String
    val descProxyServer: String
    val labelProxyCustom: String
    val descProxyCustom: String
    val labelProxyProtocol: String
    val labelProxyHost: String
    val labelProxyPort: String
    val labelProxyUser: String
    val labelProxyPassword: String
    val actionTestProxy: String
    val statusTestingProxy: String
    fun statusProxySuccess(latencyMs: Long): String
    fun statusProxyFailed(error: String): String
    val labelFoldAdvancedSync: String
    val labelUnfoldAdvancedSync: String
    val labelMyMasterKey: String
    val statusIssuingKey: String
    val actionReissueMasterKey: String
    val titlePairNewDevice: String
    val hintPairingCodeGuide: String
    fun labelPairingTimeLeft(min: Int, sec: Int): String
    val errorPairingCodeExpired: String
    val actionIssueNewCode: String
    val titleConnectWithMasterKey: String
    val titleImportExistingData: String
    val hintInput6DigitCode: String
    val placeholder6DigitCode: String
    val actionSwitchToMasterKeyMode: String
    val hintMasterKeyGuide: String
    val placeholderMasterKey: String
    val actionSwitchToPairingCodeMode: String
    val statusConnecting: String
    val actionImportData: String
    val sectionBackupAndRestore: String
    val descBackupAndRestore: String
    val actionExportBackup: String
    val actionImportBackup: String
    val titleSaveBackupFile: String
    val titleOpenBackupFile: String

    // 9. Settings
    val sectionGeneralSettings: String
    val descGeneralSettings: String
    val sectionLanguage: String
    val labelLanguage: String
    val langAuto: String
    val sectionViewerSettings: String
    val descViewerSettings: String
    val descNotificationSettings: String
    val sectionSyncAndBackup: String
    val descSyncAndBackup: String
    val sectionNetworkAndDownload: String
    val descNetworkAndDownload: String
    val sectionAppInfo: String
    val sectionAppInfoAndAbout: String
    val descAppInfoAndAbout: String
    val labelAppName: String
    fun labelAppVersion(name: String): String
    val statusCheckingUpdate: String
    val actionCheckUpdate: String
    val titleUpdateDialog: String
    fun descNewVersionAvailable(version: String): String
    fun labelVersionComparison(curr: String, latest: String): String
    val labelReleaseNotes: String
    fun labelDownloadAndApplying(percent: Int): String
    val actionUpdateNow: String
    val actionUpdateAndRestart: String
    val statusUpdatingInProgress: String
    val actionUpdateLater: String
    val labelUpdateNotification: String

    // 10. Notification Settings
    val sectionNotificationSettings: String
    val labelPeriodicUpdateNotification: String
    val descPeriodicUpdateNotification: String
    val labelNotificationInterval: String
    val interval15m: String
    val interval30m: String
    val interval1h: String
    val interval3h: String
    val interval6h: String
    val interval12h: String
    val interval24h: String
    val labelNotificationSources: String
    val descNotificationSources: String
    val labelSourceUpdateNotification: String
    val descSourceUpdateNotification: String
    val labelNotificationPermissionRequired: String
    val descNotificationPermissionRequired: String
    val actionGrantPermission: String

    // 11. Dialogs & Messages / Error / Toast
    val msgUpdateCheckFailed: String
    fun msgLatestVersionInUse(version: String): String
    val msgUpdateDownloadFailed: String
    val msgUpdateDownloadComplete: String
    val msgSyncSuccess: String
    val msgSyncFailed: String
    val msgMasterKeyReissued: String
    val msgMasterKeyReissueFailed: String
    val msgPairingCodeIssueFailed: String
    val msgInvalid6DigitCode: String
    val msgInvalidMasterKey: String
    val msgDataImportSuccess: String
    val msgDataImportFailed: String
    val msgBackupExportSuccess: String
    val msgBackupExportFailed: String
    val msgBackupRestoreSuccess: String
    val msgBackupRestoreFailed: String
    val msgConnectionTestSuccess: String
    val msgConnectionTestFailed: String
    val msgSourceSaved: String
    val msgSourceDeleted: String
    val msgSourceFolderAdded: String
    val msgSourceFolderRemoved: String
    val msgSourceImportSuccess: String
    val msgSourceImportFailed: String
    val msgHistoryCleared: String
    val msgDownloadStarted: String
    val msgDownloadFailed: String
    fun msgDownloadCompleted(title: String): String
    val msgCopiedToClipboard: String
    val msgErrorOccurred: String
}
