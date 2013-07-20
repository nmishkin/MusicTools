import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagField;
import org.jaudiotagger.tag.TagTextField;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;


public class MusicFileSync {
    enum Side {
        SOURCE,
        DESTINATION
    }
    private static final String FFMPEG = "c:/Users/mishkin/ffmpeg/bin/ffmpeg.exe";
    
    @Argument
    private List<String> arguments = new ArrayList<String>();
    private String dstRoot;
    
    public static void main(String[] args) throws IOException {
        new MusicFileSync().doMain(args);
    }
    
    @SuppressWarnings("unused")
    private static void showFileTagFields(Tag tag) {
        Iterator<TagField> fields = tag.getFields();
        while (fields.hasNext()) {
            TagField field = fields.next();
            final String value;
            if (field instanceof TagTextField) {
                TagTextField textField = (TagTextField) field;
                value = textField.getContent();
            } else {
                value = "XXX";
            }
            System.out.format("  <%s> <%s>\n", field.getId(), value);
            
        }
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
        
        dstRoot = arguments.get(0);
        
        // Collect all the files in the destination directory tree
        Map<String, Path> dstMap = new HashMap<String, Path>();
        Path dstDatPath = FS.getPath("dst.dat");
        
        if (Files.exists(dstDatPath)) {
            readMapFromFile(dstMap, dstDatPath);
        } else {
            fillMap(Side.DESTINATION, dstMap, 0, dstDatPath);
            writeMapToFile(dstMap, dstDatPath);
        }
        
        // Collect all the files at the source
        Map<String, Path> srcMap = new HashMap<String, Path>();
        Path srcDatPath = FS.getPath("src.dat");
        
        if (Files.exists(srcDatPath)) {
            readMapFromFile(srcMap, srcDatPath);
        } else {
            for (int i = 1; i < arguments.size(); i++) {
                fillMap(Side.SOURCE, srcMap, i, srcDatPath);
            }
            writeMapToFile(srcMap, srcDatPath);
        }
        int srcMapSize = srcMap.size();
        
        // Look through all the files in the source directory trees and see if they have equivalent at the destination
        int i = 0;
        for (Entry<String, Path> srcEntry : srcMap.entrySet()) {
            if (++i % 100 == 0) {
                System.out.format("====> %d of %d\n", i, srcMapSize);
            }
            
            String srcKey = srcEntry.getKey();
            Path dstPath = dstMap.get(srcKey);
            Path srcPath = srcEntry.getValue();
            if (dstPath == null) {
                copyFile(srcPath);
            } else {
                updateFile(dstPath, srcPath);
                dstMap.remove(srcKey);
            }
        }
        
        System.out.format("Unaccounted for files in destination: %d\n", dstMap.size());
        for (Entry<String, Path> dstEntry : dstMap.entrySet()) {
            Path value = dstEntry.getValue();
            deleteFile(value);
        }
    }

    private void updateFile(Path dstPath, Path srcPath) {
        try {
            AudioFile dstFile = AudioFileIO.read(dstPath.toFile());
            Tag dstTag = dstFile.getTag();
        
            AudioFile srcFile = AudioFileIO.read(srcPath.toFile());
            Tag srcTag = srcFile.getTag();
            
            boolean modified = false;
            for (Object[] pair : SyncITunesAndFiles.FIELDS) {
                FieldKey key = (FieldKey) pair[0];
                String srcField = srcTag.getFirst(key);
                String dstField = dstTag.getFirst(key);
                if (srcField != null && ! srcField.equals(dstField)) {
                    dstTag.setField(key, srcField);
                    modified = true;
                }
            }
            if (modified) {
                System.out.format("Committing update to: %s\n", dstPath);
                dstFile.commit();
            }
            
        } catch (Exception e) {
            System.out.format("Error updating file: %s\n", e);
            e.printStackTrace();
        }
        
        
        
    }

    private void copyFile(Path srcPath) {
//      System.out.format("No destination entry for source entry: %s\n", srcKey);
        try {
            String dstPath = String.format("%s/%s", dstRoot, relativize(srcPath)).replace(".m4a", ".mp3");
            new File(dstPath).getParentFile().mkdirs();
            System.out.format("ffmpeg: %s -> %s\n", srcPath, dstPath);
            String[] command = {FFMPEG, "-y", "-loglevel", "quiet", "-i", srcPath.toString(), "-id3v2_version", "3", dstPath.toString()};
            Process process = Runtime.getRuntime().exec(command);
            int status = process.waitFor();
            if (status != 0) {
                System.out.format("ffmpeg returned status %s\n", status);
            }
        } catch (Exception e) {
            System.out.format("Error copying file: %s\n", e);
            e.printStackTrace();
        }
    }

    // Turns the source path into a path relative to the source directory tree it came from.
    private Path relativize(Path srcPath) {
        for (int i = 1; i < arguments.size(); i++) {
            Path relPath = FS.getPath(arguments.get(i)).relativize(srcPath);
            if (relPath.iterator().hasNext()) {
                return relPath;
            }
        }
        throw new RuntimeException("Can't relativize: " + srcPath);
    }


    @SuppressWarnings("unused")
    private String makeNameFromTags(Path path) throws Exception {
        AudioFile file = AudioFileIO.read(path.toFile());
        Tag tag = file.getTag();        
        TagField track = tag.getFirstField(FieldKey.TRACK);
        return String.format("%s - %s - %s", 
                tag.getFirstField(FieldKey.ALBUM), 
                track, 
                tag.getFirstField(FieldKey.TITLE)).replaceAll("[^ \\-0-9A-z]", "_"); 
    }

    private static final FileSystem FS = FileSystems.getDefault();
    
    private void fillMap(Side side, Map<String, Path> map, int i, Path datPath) throws IOException {
        String dir = arguments.get(i);
        Path dirPath = FS.getPath(dir);
        System.out.format("Walking %s\n", dirPath);
        Files.walkFileTree(dirPath, new MyFileVisitor(side, dirPath, map));
    }

    private void readMapFromFile(Map<String, Path> map, Path datPath)
            throws UnsupportedEncodingException, FileNotFoundException, IOException 
    {
        System.out.format("Reading from file %s\n", datPath);
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(datPath.toFile()), "UTF-8"));  
        while (true) {
            String line = r.readLine();
            if (line == null) {
                break;
            }
            String[] split = line.split("<>");
            map.put(split[0], FS.getPath(split[1]));
        }
        r.close();
    }

    private void writeMapToFile(Map<String, Path> map, Path datPath)
            throws UnsupportedEncodingException, FileNotFoundException 
    {
        PrintStream w = new PrintStream(new FileOutputStream(datPath.toFile()), true, "UTF-8"); 
        for (Entry<String, Path> entry : map.entrySet()) {
            w.format("%s<>%s\n", entry.getKey(), entry.getValue());
        }
        w.close();
    }

    private static class MyFileVisitor implements FileVisitor<Path> {
        private Map<String, Path> map;
        private Side side;

        MyFileVisitor(Side side, Path baseDir, Map<String, Path> map) {
            this.side = side;
            this.map = map;
        }

        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            return FileVisitResult.CONTINUE;
        }

        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            return FileVisitResult.CONTINUE;
        }

        private static final Pattern SUFFIX_PATTERN = Pattern.compile("(.+(\\.(?i)(mp3|m4a))$)");
        
        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
            String pathString = path.toString();
            
            if (! SUFFIX_PATTERN.matcher(pathString).matches()) {
                return FileVisitResult.CONTINUE;
            }
            
            AudioFile file;
            try {
                file = AudioFileIO.read(path.toFile());
                Tag tag = file.getTag();
//                showFileTagFields(tag);
                
                if (side == Side.SOURCE) {
                    String genre = tag.getFirst(FieldKey.GENRE);
                    if (genre.equalsIgnoreCase("Podcast")) {
                        return FileVisitResult.CONTINUE;
                    }
                }
                
                String key = String.format("%s|%s|%s|%s", tag.getFirst(FieldKey.ALBUM), tag.getFirst(FieldKey.DISC_NO), tag.getFirst(FieldKey.TRACK), tag.getFirst(FieldKey.TITLE));
                Path prevPath = map.put(key, path);
                if (prevPath != null) {
                    System.out.format("Duplicate for key <%s>:\n  %s\n  %s\n", key, path, prevPath);
                    if (side == Side.DESTINATION) {
                        deleteFile(prevPath);
                    }
                }
            } catch (Exception e) {
                System.out.format("Error with file: %s (%s)\n", path, e.getMessage());
            }
            return FileVisitResult.CONTINUE;
        }

        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            return FileVisitResult.CONTINUE;
        }
    }

    private static void deleteFile(Path path) {
//        System.out.format("  Deleting: %s\n", path);
//        path.toFile().delete();
    }
}
