export { ChatSession, shortId, type ChatSessionOptions, type SessionSnapshot } from './model/ChatSession'
export {
    canSend,
    keyedOf,
    peersOf,
    reduceConnection,
    INITIAL_CONNECTION,
    type ConnectionEvent,
    type ConnectionState,
} from './model/connection'
export {
    assembleChunks,
    chunkCountFor,
    formatBytes,
    isRenderableImage,
    safeMimeType,
    sanitizeFileName,
    CHUNK_BYTES,
    MAX_FILE_BYTES,
    type Attachment,
    type FileChunk,
    type FileOffer,
    type TransferProgress,
} from './model/attachment'
export {
    colorOf,
    displayName,
    loadOwnProfile,
    sanitizeProfile,
    saveOwnProfile,
    EMPTY_PROFILE,
    PROFILE_COLOR_COUNT,
    PROFILE_NAME_MAX_LENGTH,
    type Profile,
} from './model/profile'
export {
    createAdmissionMemory,
    forgetAdmissionMemory,
    type AdmissionDecision,
    type AdmissionMemory,
} from './model/admissionMemory'
export type { ChatMessage, JoinRequestView, PeerId, PresenceEvent } from './model/types'
export { useChatSession } from './lib/useChatSession'
export { Avatar, OwnAvatar } from './ui/Avatar'
