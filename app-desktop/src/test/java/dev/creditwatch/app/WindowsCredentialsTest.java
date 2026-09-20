package dev.creditwatch.app;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowsCredentialsTest {
    @Test void genericCredentialRoundTripsThroughNativeContract() {
        if (Native.POINTER_SIZE == 8) assertEquals(80, new WindowsCredentials.Credential().size());
        FakeApi api = new FakeApi();
        WindowsCredentials store = new WindowsCredentials(api);
        byte[] key = "test-secret".getBytes(StandardCharsets.UTF_8);
        store.put("dev.creditwatch.vast.api-key/vast-default", key);
        assertFalse(api.writtenBlob.valid());
        assertEquals(1, api.savedType);
        assertEquals(2, api.savedPersistence);
        assertEquals("dev.creditwatch.vast.api-key/vast-default", api.target);
        assertArrayEquals(key, store.get(api.target));
        assertTrue(api.freed);
        assertNull(store.get("dev.creditwatch.vast.api-key/other"));
        store.delete(api.target);
        assertEquals(api.target, api.deletedTarget);
    }

    private static final class FakeApi implements WindowsCredentials.Api {
        String target;
        String deletedTarget;
        int savedType;
        int savedPersistence;
        byte[] saved;
        boolean freed;
        WindowsCredentials.Credential result;
        Memory blob;
        Memory writtenBlob;

        public boolean CredWrite(WindowsCredentials.Credential credential, int flags) {
            writtenBlob = (Memory) credential.blob;
            target = credential.targetName.toString();
            savedType = credential.type;
            savedPersistence = credential.persist;
            saved = credential.blob.getByteArray(0, credential.blobSize);
            return true;
        }

        public boolean CredRead(WString name, int type, int flags, PointerByReference output) {
            if (!name.toString().equals(target) || type != 1) {
                Native.setLastError(1168);
                return false;
            }
            blob = new Memory(saved.length);
            blob.write(0, saved, 0, saved.length);
            result = new WindowsCredentials.Credential();
            result.type = type;
            result.blobSize = saved.length;
            result.blob = blob;
            result.write();
            output.setValue(result.getPointer());
            return true;
        }

        public boolean CredDelete(WString name, int type, int flags) {
            deletedTarget = name.toString();
            return type == 1;
        }

        public void CredFree(Pointer pointer) {
            freed = true;
            try {
                blob.clear();
            } finally {
                blob.close();
            }
        }
    }
}
