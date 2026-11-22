package com.kongeskog.still.bridge.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/*
 * `account` is 0 for the ACI identity and 1 for the PNI identity; each has its own keys.
 * DAOs are blocking on purpose: libsignal calls the protocol stores synchronously from
 * its own threads.
 */
const val ACCOUNT_ACI = 0
const val ACCOUNT_PNI = 1

@Entity(tableName = "key_value")
class KeyValueRow(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "identity", primaryKeys = ["account", "address"])
class IdentityRow(val account: Int, val address: String, val identityKey: ByteArray, val addedAt: Long, val trust: Int)

@Entity(tableName = "session", primaryKeys = ["account", "address", "deviceId"])
class SessionRow(val account: Int, val address: String, val deviceId: Int, val record: ByteArray)

@Entity(tableName = "prekey", primaryKeys = ["account", "id"])
class PreKeyRow(val account: Int, val id: Int, val record: ByteArray, val staleAt: Long? = null)

@Entity(tableName = "signed_prekey", primaryKeys = ["account", "id"])
class SignedPreKeyRow(val account: Int, val id: Int, val record: ByteArray, val timestamp: Long)

@Entity(tableName = "kyber_prekey", primaryKeys = ["account", "id"])
class KyberPreKeyRow(
    val account: Int, val id: Int, val record: ByteArray, val timestamp: Long,
    val lastResort: Boolean, val staleAt: Long? = null,
)

@Entity(tableName = "sender_key", primaryKeys = ["address", "deviceId", "distributionId"])
class SenderKeyRow(val address: String, val deviceId: Int, val distributionId: String, val record: ByteArray, val createdAt: Long)

@Entity(tableName = "sender_key_shared", primaryKeys = ["distributionId", "address", "deviceId"])
class SenderKeySharedRow(val distributionId: String, val address: String, val deviceId: Int)

@Dao
interface KeyValueDao {
    @Query("SELECT value FROM key_value WHERE `key` = :key")
    fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(row: KeyValueRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putAll(rows: List<KeyValueRow>)

    @Query("DELETE FROM key_value WHERE `key` = :key")
    fun remove(key: String)
}

/** A last-resort Kyber prekey may be used many times, but never twice with the same base key. */
@Entity(tableName = "kyber_used", primaryKeys = ["account", "kyberId", "signedPreKeyId", "baseKey"])
class KyberUsedRow(val account: Int, val kyberId: Int, val signedPreKeyId: Int, val baseKey: ByteArray)

/** Envelopes are written here before they are acknowledged, so a crash cannot lose a message. */
@Entity(tableName = "envelope_cache")
class EnvelopeRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val envelope: ByteArray,
    val serverDeliveredTimestamp: Long,
)

/** A person. `aci` is the stable identity; the number and name may arrive later or never. */
@Entity(tableName = "recipient", indices = [Index(value = ["aci"], unique = true)])
class RecipientRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val aci: String?,
    val pni: String? = null,
    val e164: String? = null,
    /** The name the primary device has for this person (its contact list). Wins over [profileName]. */
    val name: String? = null,
    val profileKey: ByteArray? = null,
    /** The name this person chose for themselves, decrypted from their profile. */
    val profileName: String? = null,
    val profileFetchedAt: Long = 0,
    /** Counts changes to the disappearing-message timer with this person; a change with an older count is ignored by their app. */
    val expireTimerVersion: Int = 1,
)

/** A conversation: with one recipient, or a group (then `groupId` and `groupMasterKey` are set). */
@Entity(
    tableName = "thread",
    indices = [Index(value = ["recipientId"], unique = true), Index(value = ["groupId"], unique = true)],
)
class ThreadRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipientId: Long? = null,
    val groupId: String? = null,
    val groupMasterKey: ByteArray? = null,
    val title: String? = null,
    val lastMessageAt: Long = 0,
    val snippet: String? = null,
    val unread: Int = 0,
    val expireTimer: Int = 0,
    /** Revision of the group state last fetched; -1 before the first fetch. */
    val groupRevision: Int = -1,
    /** Whether this account may change the group's name and timer (an admin, or a group that lets every member). */
    val canEdit: Boolean = false,
    val muted: Boolean = false,
    val pinned: Boolean = false,
    /** Nothing from a blocked conversation is stored or announced. */
    val blocked: Boolean = false,
    /** Kept out of the main list until something new arrives. */
    val archived: Boolean = false,
)

class SearchRow(val messageId: Long, val threadId: Long, val body: String, val sentAt: Long, val groupId: String?, val title: String?, val aci: String?, val name: String?)

@Entity(tableName = "group_member", primaryKeys = ["threadId", "recipientId"])
class GroupMemberRow(val threadId: Long, val recipientId: Long)

/** `senderId` is null for messages sent from this account (any of its devices). */
@Entity(tableName = "message", indices = [Index(value = ["threadId", "sentAt"]), Index(value = ["sentAt"])])
class MessageRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    val senderId: Long?,
    val sentAt: Long,
    val receivedAt: Long,
    val body: String?,
    val attachmentCount: Int = 0,
    val status: Int = STATUS_SENT,
    val expiresIn: Int = 0,
    val expireStartedAt: Long? = null,
    /** Incoming and not yet read on any of this account's devices. */
    val unread: Boolean = false,
    /** The message this one replies to: its text, and who wrote it. */
    val quoteText: String? = null,
    val quoteAuthorId: Long? = null,
    val edited: Boolean = false,
    val quoteSentAt: Long? = null,
    /** 1 for a line in the timeline that nobody wrote: a call, a group change, a timer change. */
    val kind: Int = 0,
    /** Timestamp of the latest edit; a further edit names this, not `sentAt`. */
    val revisionAt: Long? = null,
) {
    companion object {
        const val STATUS_SENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_DELIVERED = 2
        const val STATUS_READ = 3
        const val STATUS_FAILED = 4
    }
}

/** One person's reaction to a message. `reactorId` is 0 for this account. */
@Entity(tableName = "reaction", primaryKeys = ["messageId", "reactorId"])
class ReactionRow(val messageId: Long, val reactorId: Long, val emoji: String)

/**
 * A file on a message. A received one has a `pointer` (where to fetch it, and its key) and
 * gets a `localPath` once downloaded; one being sent starts with only a `localPath`.
 */
@Entity(tableName = "attachment", indices = [Index(value = ["messageId"])])
class AttachmentRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val pointer: ByteArray?,
    val contentType: String,
    val fileName: String?,
    val size: Long,
    val voiceNote: Boolean,
    val localPath: String?,
)

/**
 * What was sent to whom, kept for a day. If a recipient's app cannot decrypt a message it
 * asks for it again by timestamp, and this is where the answer comes from.
 */
@Entity(tableName = "send_log", primaryKeys = ["recipientAci", "sentAt"])
class SendLogRow(val recipientAci: String, val sentAt: Long, val content: ByteArray, val groupId: ByteArray?, val createdAt: Long)

class ThreadListRow(
    val id: Long, val recipientId: Long?, val groupId: String?, val title: String?, val lastMessageAt: Long,
    val snippet: String?, val unread: Int, val expireTimer: Int, val canEdit: Boolean, val muted: Boolean, val pinned: Boolean, val blocked: Boolean, val archived: Boolean, val aci: String?, val e164: String?, val name: String?,
)

class MessageListRow(
    val id: Long, val senderId: Long?, val sentAt: Long, val body: String?, val attachmentCount: Int, val status: Int,
    val expiresIn: Int, val senderName: String?, val senderE164: String?,
    val kind: Int, val edited: Boolean, val quoteText: String?, val quoteAuthorAci: String?, val quoteAuthorName: String?, val reactions: String?,
)

class UnreadRow(val id: Long, val sentAt: Long, val senderAci: String?)
class ContactRow(val id: Long, val name: String?, val e164: String?)

@Dao
interface ChatDao {
    @Query("SELECT * FROM recipient WHERE aci = :aci") fun recipientByAci(aci: String): RecipientRow?
    @Insert fun insertRecipient(row: RecipientRow): Long
    @Query("UPDATE recipient SET e164 = :e164 WHERE id = :id") fun setRecipientNumber(id: Long, e164: String)
    /** A changed key means a changed profile, so the fetch stamp is cleared with it. */
    @Query("UPDATE recipient SET profileFetchedAt = CASE WHEN profileKey = :profileKey THEN profileFetchedAt ELSE 0 END, profileKey = :profileKey WHERE id = :id")
    fun setRecipientProfileKey(id: Long, profileKey: ByteArray)

    @Query("SELECT * FROM thread WHERE recipientId = :recipientId") fun threadByRecipient(recipientId: Long): ThreadRow?
    @Query("SELECT * FROM thread WHERE groupId = :groupId") fun threadByGroup(groupId: String): ThreadRow?
    @Query("SELECT * FROM thread WHERE id = :id") fun thread(id: Long): ThreadRow?
    @Insert fun insertThread(row: ThreadRow): Long
    @Query("UPDATE thread SET lastMessageAt = :at, snippet = :snippet, unread = unread + :unreadDelta, archived = CASE WHEN :unreadDelta > 0 THEN 0 ELSE archived END WHERE id = :id")
    fun touchThread(id: Long, at: Long, snippet: String?, unreadDelta: Int)
    @Query("UPDATE thread SET expireTimer = :seconds WHERE id = :id") fun setExpireTimer(id: Long, seconds: Int)
    @Query("UPDATE thread SET unread = 0 WHERE id = :id") fun markThreadRead(id: Long)

    @Query("SELECT * FROM thread WHERE id = :id") fun threadForNotification(id: Long): ThreadRow?
    @Query(
        "SELECT COALESCE(r.name, r.profileName, r.e164) FROM message m JOIN recipient r ON r.id = m.senderId " +
            "WHERE m.threadId = :threadId ORDER BY m.sentAt DESC LIMIT 1",
    )
    fun lastSenderName(threadId: Long): String?

    @Insert fun insertMessage(row: MessageRow): Long
    @Query("SELECT COUNT(*) FROM message WHERE threadId = :threadId AND sentAt = :sentAt AND senderId IS :senderId")
    fun countMessage(threadId: Long, sentAt: Long, senderId: Long?): Int
    @Query("SELECT threadId FROM message WHERE sentAt = :sentAt AND senderId IS :senderId LIMIT 1") fun threadOfMessage(sentAt: Long, senderId: Long?): Long?
    @Query("UPDATE message SET status = :status WHERE senderId IS NULL AND sentAt IN (:sentAt) AND status < :status AND status != 4")
    fun raiseStatus(sentAt: List<Long>, status: Int)

    @Query(
        "SELECT t.id, t.recipientId, t.groupId, t.title, t.lastMessageAt, t.snippet, t.unread, t.expireTimer, t.canEdit, t.muted, t.pinned, t.blocked, t.archived, r.aci, r.e164, COALESCE(r.name, r.profileName) AS name " +
            "FROM thread t LEFT JOIN recipient r ON r.id = t.recipientId WHERE t.archived = :archived AND (t.lastMessageAt > 0 OR t.groupId IS NOT NULL OR t.pinned = 1) " +
            "ORDER BY t.pinned DESC, t.lastMessageAt DESC, t.title COLLATE NOCASE",
    )
    fun threads(archived: Boolean): Flow<List<ThreadListRow>>
    @Query("SELECT COUNT(*) FROM thread WHERE archived = 1 AND (lastMessageAt > 0 OR groupId IS NOT NULL)") fun archivedCount(): Flow<Int>
    @Query("UPDATE thread SET archived = :archived WHERE id = :id") fun setArchived(id: Long, archived: Boolean)

    @Query(
        "SELECT * FROM (SELECT m.id, m.senderId, m.sentAt, m.body, m.attachmentCount, m.status, m.expiresIn, COALESCE(r.name, r.profileName) AS senderName, r.e164 AS senderE164, " +
            "m.kind, m.edited, m.quoteText, q.aci AS quoteAuthorAci, COALESCE(q.name, q.profileName, q.e164) AS quoteAuthorName, " +
            "(SELECT GROUP_CONCAT(emoji, ' ') FROM reaction WHERE messageId = m.id) AS reactions " +
            "FROM message m LEFT JOIN recipient r ON r.id = m.senderId LEFT JOIN recipient q ON q.id = m.quoteAuthorId WHERE m.threadId = :threadId ORDER BY m.sentAt DESC LIMIT :limit) ORDER BY sentAt",
    )
    fun messages(threadId: Long, limit: Int): Flow<List<MessageListRow>>

    // Names
    @Query("SELECT * FROM recipient WHERE id = :id") fun recipient(id: Long): RecipientRow?
    @Query("UPDATE recipient SET name = :name WHERE id = :id") fun setRecipientName(id: Long, name: String?)
    @Query("UPDATE recipient SET profileName = :name WHERE id = :id AND profileName IS NULL") fun setProfileNameIfMissing(id: Long, name: String)
    @Query("UPDATE recipient SET profileName = :name, profileFetchedAt = :at WHERE id = :id") fun setProfile(id: Long, name: String?, at: Long)
    @Query("SELECT * FROM recipient WHERE aci IS NOT NULL AND profileKey IS NOT NULL AND profileFetchedAt < :before LIMIT :limit")
    fun recipientsNeedingProfile(before: Long, limit: Int): List<RecipientRow>
    @Query("SELECT * FROM thread WHERE groupMasterKey IS NOT NULL AND groupRevision < 0") fun groupsNeverFetched(): List<ThreadRow>
    @Query("UPDATE thread SET title = :title, groupRevision = :revision, expireTimer = :expireTimer, canEdit = :canEdit WHERE id = :id")
    fun setGroup(id: Long, title: String?, revision: Int, expireTimer: Int, canEdit: Boolean)
    /** Makes the group fetch run again; used when a message carries a newer revision. */
    @Query("UPDATE thread SET groupRevision = -1 WHERE id = :id AND groupRevision < :revision") fun markGroupStale(id: Long, revision: Int)
    @Query("DELETE FROM group_member WHERE threadId = :threadId") fun clearMembers(threadId: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertMembers(rows: List<GroupMemberRow>)
    @Query(
        "SELECT r.id, COALESCE(r.name, r.profileName) AS name, r.e164 FROM group_member g JOIN recipient r ON r.id = g.recipientId " +
            "WHERE g.threadId = :threadId ORDER BY COALESCE(r.name, r.profileName, r.e164, 'zzz') COLLATE NOCASE",
    )
    fun members(threadId: Long): List<ContactRow>

    // Reading and expiry
    @Query("SELECT m.id, m.sentAt, r.aci AS senderAci FROM message m LEFT JOIN recipient r ON r.id = m.senderId WHERE m.threadId = :threadId AND m.unread = 1")
    fun unreadMessages(threadId: Long): List<UnreadRow>
    @Query("UPDATE message SET unread = 0, expireStartedAt = CASE WHEN expiresIn > 0 AND expireStartedAt IS NULL THEN :now ELSE expireStartedAt END WHERE threadId = :threadId AND unread = 1")
    fun markMessagesRead(threadId: Long, now: Long)
    @Query("UPDATE message SET unread = 0, expireStartedAt = CASE WHEN expiresIn > 0 AND expireStartedAt IS NULL THEN :now ELSE expireStartedAt END WHERE threadId = :threadId AND unread = 1 AND sentAt <= :upTo")
    fun markMessagesReadUpTo(threadId: Long, upTo: Long, now: Long)
    @Query("SELECT COUNT(*) FROM message WHERE threadId = :threadId AND unread = 1") fun countUnread(threadId: Long): Int
    @Query("UPDATE thread SET unread = :unread WHERE id = :id") fun setThreadUnread(id: Long, unread: Int)
    @Query("SELECT DISTINCT threadId FROM message WHERE expiresIn > 0 AND expireStartedAt IS NOT NULL AND expireStartedAt + expiresIn * 1000 <= :now")
    fun threadsWithExpired(now: Long): List<Long>
    @Query("DELETE FROM message WHERE expiresIn > 0 AND expireStartedAt IS NOT NULL AND expireStartedAt + expiresIn * 1000 <= :now")
    fun deleteExpired(now: Long): Int
    @Query("SELECT MIN(expireStartedAt + expiresIn * 1000) FROM message WHERE expiresIn > 0 AND expireStartedAt IS NOT NULL")
    fun nextExpiry(): Long?
    @Query("SELECT * FROM message WHERE threadId = :threadId ORDER BY sentAt DESC LIMIT 1") fun lastMessage(threadId: Long): MessageRow?
    @Query("UPDATE thread SET snippet = :snippet WHERE id = :id") fun setSnippet(id: Long, snippet: String?)

    // Reactions, edits, deletes
    @Query("SELECT * FROM message WHERE (sentAt = :sentAt OR revisionAt = :sentAt) AND senderId IS :senderId LIMIT 1") fun messageBy(sentAt: Long, senderId: Long?): MessageRow?
    @Query("DELETE FROM message WHERE id = :id") fun deleteMessage(id: Long)
    @Query("UPDATE message SET body = :body, edited = 1, revisionAt = :revisionAt WHERE id = :id") fun editMessage(id: Long, body: String?, revisionAt: Long)
    @Query("UPDATE message SET body = :body WHERE id = :id") fun setBody(id: Long, body: String)
    @Query("DELETE FROM attachment WHERE id = :id") fun deleteAttachment(id: Long)
    @Query("UPDATE thread SET muted = :muted WHERE id = :id") fun setMuted(id: Long, muted: Boolean)
    @Query("DELETE FROM message WHERE threadId = :threadId") fun deleteMessages(threadId: Long)
    @Query("UPDATE thread SET lastMessageAt = 0, snippet = NULL, unread = 0 WHERE id = :id") fun clearThread(id: Long)
    @Query("SELECT COALESCE(name, profileName, e164) FROM recipient WHERE aci = :aci") fun nameOf(aci: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putReaction(row: ReactionRow)
    @Query("DELETE FROM reaction WHERE messageId = :messageId AND reactorId = :reactorId") fun removeReaction(messageId: Long, reactorId: Long)
    @Query("DELETE FROM reaction WHERE messageId NOT IN (SELECT id FROM message)") fun deleteOrphanReactions()
    @Query("SELECT r.aci FROM group_member g JOIN recipient r ON r.id = g.recipientId WHERE g.threadId = :threadId AND r.aci IS NOT NULL")
    fun memberAcis(threadId: Long): List<String>

    @Query("SELECT * FROM message WHERE id = :id") fun message(id: Long): MessageRow?
    @Query("SELECT emoji FROM reaction WHERE messageId = :messageId AND reactorId = 0") fun ownReaction(messageId: Long): String?
    @Query("UPDATE recipient SET expireTimerVersion = :version WHERE id = :id AND expireTimerVersion < :version") fun raiseTimerVersion(id: Long, version: Int)
    @Query("SELECT id FROM thread WHERE recipientId = (SELECT id FROM recipient WHERE aci = :aci)") fun threadIdByAci(aci: String): Long?

    // Attachments
    @Insert fun insertAttachments(rows: List<AttachmentRow>)
    @Query("SELECT a.* FROM attachment a JOIN message m ON m.id = a.messageId WHERE m.threadId = :threadId") fun attachments(threadId: Long): Flow<List<AttachmentRow>>
    @Query("SELECT * FROM attachment WHERE messageId = :messageId") fun attachmentsOf(messageId: Long): List<AttachmentRow>
    @Query("SELECT * FROM attachment WHERE id = :id") fun attachment(id: Long): AttachmentRow?
    @Query("UPDATE attachment SET localPath = :path WHERE id = :id") fun setAttachmentPath(id: Long, path: String)
    @Query("UPDATE attachment SET pointer = :pointer WHERE id = :id") fun setAttachmentPointer(id: Long, pointer: ByteArray)
    @Query("SELECT localPath FROM attachment WHERE localPath IS NOT NULL AND messageId NOT IN (SELECT id FROM message)") fun orphanAttachmentPaths(): List<String>
    @Query("DELETE FROM attachment WHERE messageId NOT IN (SELECT id FROM message)") fun deleteOrphanAttachments()

    @Query(
        "SELECT a.* FROM attachment a JOIN message m ON m.id = a.messageId WHERE a.localPath IS NULL AND a.pointer IS NOT NULL " +
            "AND (a.voiceNote = 1 OR a.contentType LIKE 'image/%' OR a.contentType LIKE 'text/x-signal-plain%') AND a.size <= :maxSize AND m.receivedAt > :since",
    )
    fun attachmentsToFetch(maxSize: Long, since: Long): List<AttachmentRow>

    @Query("UPDATE thread SET pinned = :pinned WHERE id = :id") fun setPinned(id: Long, pinned: Boolean)
    @Query("UPDATE thread SET pinned = 0") fun clearPins()
    @Query("UPDATE thread SET blocked = :blocked WHERE id = :id") fun setBlocked(id: Long, blocked: Boolean)
    @Query(
        "SELECT m.id AS messageId, m.threadId, m.body, m.sentAt, t.groupId, t.title, r.aci, COALESCE(r.name, r.profileName, r.e164) AS name " +
            "FROM message m JOIN thread t ON t.id = m.threadId LEFT JOIN recipient r ON r.id = t.recipientId " +
            "WHERE m.kind = 0 AND m.body LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY m.sentAt DESC LIMIT 50",
    )
    fun search(query: String): List<SearchRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putSendLog(row: SendLogRow)
    @Query("SELECT * FROM send_log WHERE recipientAci = :aci AND sentAt = :sentAt") fun sendLog(aci: String, sentAt: Long): SendLogRow?
    @Query("DELETE FROM send_log WHERE createdAt < :before") fun pruneSendLog(before: Long)

    // Sending
    @Query("SELECT * FROM message WHERE senderId IS NULL AND status = 0 ORDER BY sentAt") fun pendingSends(): List<MessageRow>
    @Query("UPDATE message SET status = :status WHERE id = :id") fun setStatus(id: Long, status: Int)
    @Query("UPDATE message SET status = 0 WHERE id = :id AND status = 4") fun retry(id: Long)
    @Query("UPDATE message SET status = 4 WHERE senderId IS NULL AND status = 0 AND sentAt < :before") fun failOldSends(before: Long)

    // Contacts
    @Query(
        "SELECT id, COALESCE(name, profileName) AS name, e164 FROM recipient WHERE aci IS NOT NULL AND aci != :selfAci " +
            "AND COALESCE(name, profileName) IS NOT NULL ORDER BY COALESCE(name, profileName) COLLATE NOCASE",
    )
    fun contacts(selfAci: String): Flow<List<ContactRow>>
    @Query(
        "SELECT t.id, t.recipientId, t.groupId, t.title, t.lastMessageAt, t.snippet, t.unread, t.expireTimer, t.canEdit, t.muted, t.pinned, t.blocked, t.archived, r.aci, r.e164, COALESCE(r.name, r.profileName) AS name " +
            "FROM thread t LEFT JOIN recipient r ON r.id = t.recipientId WHERE t.id = :id",
    )
    fun threadSummary(id: Long): Flow<ThreadListRow?>
}

@Dao
interface PreKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putSigned(row: SignedPreKeyRow)
    @Query("SELECT * FROM signed_prekey WHERE account = :account AND id = :id") fun signed(account: Int, id: Int): SignedPreKeyRow?
    @Query("SELECT * FROM signed_prekey WHERE account = :account") fun allSigned(account: Int): List<SignedPreKeyRow>
    @Query("DELETE FROM signed_prekey WHERE account = :account AND id = :id") fun removeSigned(account: Int, id: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putKyber(row: KyberPreKeyRow)
    @Query("SELECT * FROM kyber_prekey WHERE account = :account AND id = :id") fun kyber(account: Int, id: Int): KyberPreKeyRow?
    @Query("SELECT * FROM kyber_prekey WHERE account = :account") fun allKyber(account: Int): List<KyberPreKeyRow>
    @Query("SELECT * FROM kyber_prekey WHERE account = :account AND lastResort = 1") fun lastResortKyber(account: Int): List<KyberPreKeyRow>
    @Query("DELETE FROM kyber_prekey WHERE account = :account AND id = :id") fun removeKyber(account: Int, id: Int)
    @Query("UPDATE kyber_prekey SET staleAt = :time WHERE account = :account AND lastResort = 0 AND staleAt IS NULL")
    fun markKyberStale(account: Int, time: Long)
    @Query(
        "DELETE FROM kyber_prekey WHERE account = :account AND lastResort = 0 AND staleAt IS NOT NULL AND staleAt < :threshold " +
            "AND id NOT IN (SELECT id FROM kyber_prekey WHERE account = :account AND lastResort = 0 ORDER BY staleAt DESC LIMIT :keep)",
    )
    fun deleteStaleKyber(account: Int, threshold: Long, keep: Int)
    @Insert(onConflict = OnConflictStrategy.ABORT) fun putKyberUsed(row: KyberUsedRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putPreKey(row: PreKeyRow)
    @Query("SELECT * FROM prekey WHERE account = :account AND id = :id") fun preKey(account: Int, id: Int): PreKeyRow?
    @Query("DELETE FROM prekey WHERE account = :account AND id = :id") fun removePreKey(account: Int, id: Int)
    @Query("UPDATE prekey SET staleAt = :time WHERE account = :account AND staleAt IS NULL") fun markPreKeysStale(account: Int, time: Long)
    @Query(
        "DELETE FROM prekey WHERE account = :account AND staleAt IS NOT NULL AND staleAt < :threshold " +
            "AND id NOT IN (SELECT id FROM prekey WHERE account = :account ORDER BY staleAt DESC LIMIT :keep)",
    )
    fun deleteStalePreKeys(account: Int, threshold: Long, keep: Int)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM identity WHERE account = :account AND address = :address") fun identity(account: Int, address: String): IdentityRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putIdentity(row: IdentityRow)

    @Query("SELECT * FROM session WHERE account = :account AND address = :address AND deviceId = :deviceId")
    fun session(account: Int, address: String, deviceId: Int): SessionRow?
    @Query("SELECT * FROM session WHERE account = :account AND address IN (:addresses)") fun sessions(account: Int, addresses: List<String>): List<SessionRow>
    @Query("SELECT deviceId FROM session WHERE account = :account AND address = :address") fun sessionDevices(account: Int, address: String): List<Int>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putSession(row: SessionRow)
    @Query("DELETE FROM session WHERE account = :account AND address = :address AND deviceId = :deviceId") fun removeSession(account: Int, address: String, deviceId: Int)
    @Query("DELETE FROM session WHERE account = :account AND address = :address") fun removeSessions(account: Int, address: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putSenderKey(row: SenderKeyRow)
    @Query("SELECT * FROM sender_key WHERE address = :address AND deviceId = :deviceId AND distributionId = :distributionId")
    fun senderKey(address: String, deviceId: Int, distributionId: String): SenderKeyRow?
    @Query("SELECT * FROM sender_key_shared WHERE distributionId = :distributionId") fun senderKeyShared(distributionId: String): List<SenderKeySharedRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putSenderKeyShared(rows: List<SenderKeySharedRow>)
    @Query("DELETE FROM sender_key_shared WHERE address = :address AND deviceId = :deviceId") fun clearSenderKeyShared(address: String, deviceId: Int)
}

@Dao
interface EnvelopeDao {
    @Insert fun put(row: EnvelopeRow): Long
    @Query("SELECT * FROM envelope_cache ORDER BY id LIMIT :limit") fun oldest(limit: Int): List<EnvelopeRow>
    @Query("DELETE FROM envelope_cache WHERE id = :id") fun remove(id: Long)
}

/** Version 1 was the linking-only schema; real accounts exist on it, so it is migrated, never dropped. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE prekey ADD COLUMN staleAt INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `kyber_used` (`account` INTEGER NOT NULL, `kyberId` INTEGER NOT NULL, " +
                "`signedPreKeyId` INTEGER NOT NULL, `baseKey` BLOB NOT NULL, PRIMARY KEY(`account`, `kyberId`, `signedPreKeyId`, `baseKey`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `envelope_cache` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`envelope` BLOB NOT NULL, `serverDeliveredTimestamp` INTEGER NOT NULL)",
        )
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    // Statements copied from schemas/.../3.json, which Room validates against.
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `recipient` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `aci` TEXT, `pni` TEXT, `e164` TEXT, `name` TEXT, `profileKey` BLOB)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recipient_aci` ON `recipient` (`aci`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `thread` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `recipientId` INTEGER, `groupId` TEXT, `groupMasterKey` BLOB, `title` TEXT, `lastMessageAt` INTEGER NOT NULL, `snippet` TEXT, `unread` INTEGER NOT NULL, `expireTimer` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_thread_recipientId` ON `thread` (`recipientId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_thread_groupId` ON `thread` (`groupId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `message` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `threadId` INTEGER NOT NULL, `senderId` INTEGER, `sentAt` INTEGER NOT NULL, `receivedAt` INTEGER NOT NULL, `body` TEXT, `attachmentCount` INTEGER NOT NULL, `status` INTEGER NOT NULL, `expiresIn` INTEGER NOT NULL, `expireStartedAt` INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_threadId_sentAt` ON `message` (`threadId`, `sentAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_sentAt` ON `message` (`sentAt`)")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recipient ADD COLUMN profileName TEXT")
        db.execSQL("ALTER TABLE recipient ADD COLUMN profileFetchedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE thread ADD COLUMN groupRevision INTEGER NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE message ADD COLUMN unread INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS `group_member` (`threadId` INTEGER NOT NULL, `recipientId` INTEGER NOT NULL, PRIMARY KEY(`threadId`, `recipientId`))")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE message ADD COLUMN quoteText TEXT")
        db.execSQL("ALTER TABLE message ADD COLUMN quoteAuthorId INTEGER")
        db.execSQL("ALTER TABLE message ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS `reaction` (`messageId` INTEGER NOT NULL, `reactorId` INTEGER NOT NULL, `emoji` TEXT NOT NULL, PRIMARY KEY(`messageId`, `reactorId`))")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE message ADD COLUMN quoteSentAt INTEGER")
        db.execSQL("ALTER TABLE recipient ADD COLUMN expireTimerVersion INTEGER NOT NULL DEFAULT 1")
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `attachment` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `messageId` INTEGER NOT NULL, `pointer` BLOB, `contentType` TEXT NOT NULL, `fileName` TEXT, `size` INTEGER NOT NULL, `voiceNote` INTEGER NOT NULL, `localPath` TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachment_messageId` ON `attachment` (`messageId`)")
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE thread ADD COLUMN canEdit INTEGER NOT NULL DEFAULT 0")
        // Fetch every group again, so the new column gets its real value.
        db.execSQL("UPDATE thread SET groupRevision = -1 WHERE groupMasterKey IS NOT NULL")
    }
}

private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE message ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE message ADD COLUMN revisionAt INTEGER")
        db.execSQL("ALTER TABLE thread ADD COLUMN muted INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE thread ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE thread ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0")
        // Read the storage service again: it says which conversations are pinned and blocked.
        db.execSQL("DELETE FROM key_value WHERE `key` = 'storage.version'")
    }
}

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE thread ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("DELETE FROM key_value WHERE `key` = 'storage.version'")
    }
}

private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `send_log` (`recipientAci` TEXT NOT NULL, `sentAt` INTEGER NOT NULL, `content` BLOB NOT NULL, `groupId` BLOB, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`recipientAci`, `sentAt`))")
    }
}

@Database(
    entities = [
        KeyValueRow::class, IdentityRow::class, SessionRow::class, PreKeyRow::class,
        SignedPreKeyRow::class, KyberPreKeyRow::class, SenderKeyRow::class, SenderKeySharedRow::class,
        KyberUsedRow::class, EnvelopeRow::class, RecipientRow::class, ThreadRow::class, MessageRow::class, GroupMemberRow::class, ReactionRow::class, AttachmentRow::class, SendLogRow::class,
    ],
    version = 12,
    exportSchema = true,
)
abstract class StillDatabase : RoomDatabase() {
    abstract fun keyValue(): KeyValueDao
    abstract fun preKeys(): PreKeyDao
    abstract fun sessions(): SessionDao
    abstract fun envelopes(): EnvelopeDao
    abstract fun chats(): ChatDao

    companion object {
        fun open(context: Context): StillDatabase =
            Room.databaseBuilder(context.applicationContext, StillDatabase::class.java, "still.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                .build()
    }
}
