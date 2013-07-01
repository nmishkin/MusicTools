import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;


public class MusicFileSync {
    @Argument
    private List<String> arguments = new ArrayList<String>();
    
    public static void main(String[] args) throws IOException {
        new MusicFileSync().doMain(args);
    }
    
    public void doMain(String[] args) throws IOException {
        CmdLineParser parser = new CmdLineParser(this);
        
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            parser.printUsage(System.err);
            System.err.println();
            return;
        }
        
        
        Map<String, Path> dstMap = new HashMap<String, Path>();
        Path dstDatPath = fs.getPath("dst.dat");
        fillMap(dstMap, 0, dstDatPath);
        writeMap(dstMap, dstDatPath);
        
        Map<String, Path> srcMap = new HashMap<String, Path>();
        Path srcDatPath = fs.getPath("src.dat");
        for (int i = 1; i < arguments.size(); i++) {
            fillMap(srcMap, i, srcDatPath);
        }
        writeMap(srcMap, srcDatPath);
        

    }

    private static FileSystem fs = FileSystems.getDefault();
    
    private void fillMap(Map<String, Path> map, int i, Path datPath) throws IOException {
        if (Files.exists(datPath)) {
            System.out.format("Reading from file %s\n", datPath);
            BufferedReader r = new BufferedReader(new FileReader(datPath.toFile()));
            while (true) {
                String line = r.readLine();
                if (line == null) {
                    break;
                }
                String[] split = line.split("<>");
                map.put(split[0], fs.getPath(split[1]));
            }
            r.close();
        } else {
            String dir = arguments.get(i);
            Path dirPath = fs.getPath(dir);
            System.out.format("Walking %s\n", dirPath);
            Files.walkFileTree(dirPath, new MyFileVisitor(dirPath, map));
        }
    }

    private void writeMap(Map<String, Path> map, Path datPath)
            throws UnsupportedEncodingException, FileNotFoundException {
        if (Files.exists(datPath)) {
            return;
        }
        PrintStream w = new PrintStream(new FileOutputStream(datPath.toFile()), true, "UTF-8"); 
        for (Entry<String, Path> entry : map.entrySet()) {
            w.format("%s<>%s\n", entry.getKey(), entry.getValue());
        }
        w.close();
    }

    private static class MyFileVisitor implements FileVisitor<Path> {
        private Map<String, Path> map;
        private Path baseDir;

        MyFileVisitor(Path baseDir, Map<String, Path> map) {
            this.map = map;
            this.baseDir = baseDir;
        }

        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            return FileVisitResult.CONTINUE;
        }

        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            return FileVisitResult.CONTINUE;
        }

        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
            String pathString = path.toString();
            
            int length = pathString.length();
            if (! pathString.endsWith(".mp3") && ! pathString.substring(length - 4, length - 1).equals(".m4")) {
                return FileVisitResult.CONTINUE;
            }
            
            AudioFile file;
            try {
                file = AudioFileIO.read(path.toFile());
                Tag tag = file.getTag();
                String key = String.format("%s|%s|%s", tag.getFirst(FieldKey.ALBUM), tag.getFirst(FieldKey.TRACK), tag.getFirst(FieldKey.TITLE));
                map.put(key, baseDir.relativize(path));
            } catch (Exception e) {
                System.err.format("Error with file: %s\n", path);
//                e.printStackTrace();
            }
            return FileVisitResult.CONTINUE;
        }

        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            return FileVisitResult.CONTINUE;
        }
        
    }


}
