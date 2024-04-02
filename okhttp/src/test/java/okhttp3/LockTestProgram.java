package okhttp3;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

public class LockTestProgram {
  public static void main(String[] args) throws IOException {
    File lockFile = new File(args[0]);

    System.out.println("Locking " + lockFile);

    FileChannel channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.APPEND);

    channel.lock();

    System.out.println("Locked " + lockFile);

    System.in.read();
  }
}
