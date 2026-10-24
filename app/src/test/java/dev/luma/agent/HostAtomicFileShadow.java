package dev.luma.agent;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

/** Android's POSIX rename replaces an existing file; Windows File.renameTo does not. */
@Implements(AtomicFile.class)
public class HostAtomicFileShadow {
    @RealObject private AtomicFile file;
    @Implementation protected void finishWrite(FileOutputStream stream) {
        if (stream == null) return;
        try {
            stream.getFD().sync();
            stream.close();
            File pending = new File(file.getBaseFile().getPath() + ".new");
            if (pending.exists()) Files.move(pending.toPath(), file.getBaseFile().toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(new File(file.getBaseFile().getPath() + ".bak").toPath());
        } catch (IOException e) { throw new RuntimeException(e); }
    }
}
