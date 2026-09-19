package dev.claudefleet.mobile.store

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS's secure store: one generic-password Keychain item, holding the
 * credential as JSON.
 *
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` is the accessibility
 * chosen, and both halves of it matter. *AfterFirstUnlock* so that the app can
 * still reach the hub when it is woken in the background with the screen
 * locked, which a live session list needs. *ThisDeviceOnly* so the item is
 * excluded from iCloud Keychain and from encrypted backups: a token that
 * silently followed a restore onto a second device would be a credential the
 * operator never issued and cannot see.
 *
 * Nothing here logs, and [KeychainFailure] carries an `OSStatus` — never the
 * value it failed to store.
 *
 * **Linked, never run.** This file compiles for `iosArm64` and
 * `iosSimulatorArm64` and is linked into `iosApp` by both a local `xcodebuild`
 * and the `macos` CI job — the Keychain calls themselves have never executed.
 * See `README.md` → *What a Mac still has to check*.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainSecrets(
    private val service: String = SECRETS_SERVICE,
    private val account: String = SECRETS_ACCOUNT,
) : Secrets {

    override suspend fun read(): Credentials? = withContext(Dispatchers.Default) {
        decodeCredentials(load())
    }

    override suspend fun write(credentials: Credentials) {
        val encoded = credentials.encode()
        withContext(Dispatchers.Default) { save(encoded) }
    }

    override suspend fun clear() {
        withContext(Dispatchers.Default) {
            withQuery { query -> requireDeleted(SecItemDelete(query)) }
        }
    }

    // ---- the Keychain ----

    /**
     * Build the query identifying this app's one item, hand it to [block], and
     * release every Core Foundation object created for it afterwards — the
     * `Create` functions return a +1 reference that nothing else owns.
     */
    private fun <T> withQuery(block: (CFMutableDictionaryRef) -> T): T {
        val serviceRef = cfString(service)
        val accountRef = cfString(account)
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, serviceRef)
        CFDictionarySetValue(query, kSecAttrAccount, accountRef)
        return try {
            block(query)
        } finally {
            CFRelease(query)
            serviceRef?.let { CFRelease(it) }
            accountRef?.let { CFRelease(it) }
        }
    }

    private fun load(): String? = withQuery { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped null
            val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
            try {
                val bytes: CPointer<ByteVar> = CFDataGetBytePtr(data)?.reinterpret()
                    ?: return@memScoped null
                val length = CFDataGetLength(data).toInt()
                if (length <= 0) null else bytes.readBytes(length).decodeToString()
            } finally {
                // `kSecReturnData` hands back a +1 reference.
                CFRelease(data)
            }
        }
    }

    /**
     * A `SecItemDelete` status that means the item is gone.
     *
     * `errSecItemNotFound` is benign — there was nothing to delete. Anything
     * else is not, and review finding S3 names the reachable case:
     * `errSecInteractionNotAllowed`, when the device has not been unlocked since
     * boot. The item is `AfterFirstUnlock` precisely so a background wake can
     * read it, so a background wake before the *first* unlock is exactly the
     * situation this accessibility choice invites — and a discarded status there
     * makes `clear()` a silent no-op while `AppSession.forget()` has already
     * published `Unpaired`.
     */
    private fun requireDeleted(status: Int) {
        if (status != errSecSuccess && status != errSecItemNotFound) throw KeychainFailure(status)
    }

    private fun save(value: String) {
        // Delete then add, rather than branching on whether an item exists:
        // one path, and no window where a failed update leaves the old token.
        // The status is checked for the same reason `clear()` checks it: a
        // delete that quietly failed would make the `SecItemAdd` below return
        // `errSecDuplicateItem` and leave the *old* token in place.
        withQuery { query -> requireDeleted(SecItemDelete(query)) }

        val bytes = value.encodeToByteArray()
        if (bytes.isEmpty()) return
        val data = bytes.usePinned { pinned ->
            CFDataCreate(
                kCFAllocatorDefault,
                pinned.addressOf(0).reinterpret(),
                bytes.size.convert(),
            )
        }
        try {
            withQuery { query ->
                CFDictionarySetValue(query, kSecValueData, data)
                CFDictionarySetValue(
                    query,
                    kSecAttrAccessible,
                    kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                )
                val status = SecItemAdd(query, null)
                if (status != errSecSuccess) throw KeychainFailure(status)
            }
        } finally {
            data?.let { CFRelease(it) }
        }
    }

    private fun cfString(value: String): CFStringRef? = memScoped {
        // `CFStringCreateWithCString` copies, so the scoped C string may go.
        CFStringCreateWithCString(kCFAllocatorDefault, value.cstr.ptr, kCFStringEncodingUTF8)
    }
}

/**
 * The Keychain refused an operation. Carries Apple's `OSStatus` and nothing
 * else — never the value that was being stored.
 *
 * A [SecretsUnavailable], because that is the contract [Secrets.clear] states
 * and the one Android already keeps. As a bare `Exception` this fell through
 * `explain()`'s whitelist to "something went wrong (KeychainFailure)", while
 * the identical Android failure read "the credential could not be removed" —
 * and the case is reachable by this file's own documentation:
 * `errSecInteractionNotAllowed` on a background wake before the device's first
 * unlock, which is exactly the situation `AfterFirstUnlock` invites. It can
 * make the promise honestly: the message carries an `OSStatus` and never a
 * stored value.
 */
class KeychainFailure(val status: Int) :
    SecretsUnavailable("the iOS Keychain refused the operation (OSStatus $status)")
