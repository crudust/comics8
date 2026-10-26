/**
 * Storage providers: local archives (ZIP/CBZ) and network libraries (WebDAV).
 */

export {
  ARCHIVE_EXTENSIONS,
  IMAGE_EXTENSIONS,
  ZipProvider,
  archiveExtension,
  baseName,
  isArchiveName,
  isImageEntry,
  isJunkEntry,
  isJunkName,
  listImageEntries,
  mimeTypeFor,
  naturalCompare,
  sortPageNames,
  type ComicPage,
  type OpenArchiveOptions,
  type ZipEntryInfo,
} from "./zip-provider";

export {
  WebDavProvider,
  classifyEntry,
  joinPath,
  normalizePath,
  parseMultiStatus,
  type WebDavConfig,
  type WebDavEntry,
  type WebDavEntryKind,
  type WebDavImageDirectory,
} from "./webdav-provider";
