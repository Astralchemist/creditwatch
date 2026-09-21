package dev.creditwatch.app;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/** Minimal Unicode binding to the current user's Windows Credential Manager. */
final class WindowsCredentials {
    private static final int GENERIC = 1;
    private static final int LOCAL_MACHINE = 2;
    private static final int NOT_FOUND = 1168;
    private static final int MAX_BLOB_BYTES = 2560;

    interface Api extends StdCallLibrary {
        boolean CredWrite(Credential credential, int flags);
        boolean CredRead(WString target, int type, int flags, PointerByReference result);
        boolean CredDelete(WString target, int type, int flags);
        void CredFree(Pointer credential);
    }

    @Structure.FieldOrder({"flags", "type", "targetName", "comment", "lastWritten", "blobSize",
        "blob", "persist", "attributeCount", "attributes", "targetAlias", "userName"})
    public static class Credential extends Structure {
        public int flags;
        public int type;
        public WString targetName;
        public WString comment;
        public FileTime lastWritten = new FileTime();
        public int blobSize;
        public Pointer blob;
        public int persist;
        public int attributeCount;
        public Pointer attributes;
        public WString targetAlias;
        public WString userName;

        public Credential() { }
        public Credential(Pointer pointer) {
            useMemory(pointer);
            read();
        }
    }

    @Structure.FieldOrder({"low", "high"})
    public static class FileTime extends Structure {
        public int low;
        public int high;
    }

    private final Api api;

    WindowsCredentials() {
        this(Native.load("Advapi32", Api.class, W32APIOptions.UNICODE_OPTIONS));
    }

    WindowsCredentials(Api api) {
        this.api = api;
    }

    void put(String target, byte[] value) {
        if (value.length == 0 || value.length > MAX_BLOB_BYTES) {
            throw new IllegalArgumentException("API key has an unsupported size");
        }
        Memory blob = new Memory(value.length);
        try {
            blob.write(0, value, 0, value.length);
            Credential credential = new Credential();
            credential.type = GENERIC;
            credential.targetName = new WString(target);
            credential.blobSize = value.length;
            credential.blob = blob;
            credential.persist = LOCAL_MACHINE;
            credential.userName = new WString("CreditWatch");
            if (!api.CredWrite(credential, 0)) throw failure("save");
        } finally {
            try {
                blob.clear();
            } finally {
                blob.close();
            }
        }
    }

    byte[] get(String target) {
        PointerByReference result = new PointerByReference();
        if (!api.CredRead(new WString(target), GENERIC, 0, result)) {
            if (Native.getLastError() == NOT_FOUND) return null;
            throw failure("read");
        }
        Pointer pointer = result.getValue();
        try {
            Credential credential = new Credential(pointer);
            if (credential.blobSize <= 0 || credential.blobSize > MAX_BLOB_BYTES || credential.blob == null) {
                throw new IllegalStateException("Windows Credential Manager returned an invalid API key");
            }
            return credential.blob.getByteArray(0, credential.blobSize);
        } finally {
            api.CredFree(pointer);
        }
    }

    void delete(String target) {
        if (!api.CredDelete(new WString(target), GENERIC, 0) && Native.getLastError() != NOT_FOUND) {
            throw failure("delete");
        }
    }

    private static SecureStorageUnavailableException failure(String action) {
        return new SecureStorageUnavailableException("Could not " + action + " the API key in Windows Credential Manager. Check that Windows Credential Manager is available.");
    }
}
