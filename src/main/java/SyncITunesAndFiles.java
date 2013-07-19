import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.CannotWriteException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldDataInvalidException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.KeyNotFoundException;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;
import org.jaudiotagger.tag.TagField;
import org.jaudiotagger.tag.TagTextField;
import org.jvnet.com4j.generated.ClassFactory;
import org.jvnet.com4j.generated.IITFileOrCDTrack;
import org.jvnet.com4j.generated.IITLibraryPlaylist;
import org.jvnet.com4j.generated.IITTrackCollection;
import org.jvnet.com4j.generated.IiTunes;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

public class SyncITunesAndFiles {
    
    @Option(name="-updateFile")
    private boolean updateFile;
    
    @Option(name="-updateITunes")
    private boolean updateITunes;

    public static void main(String[] args) {
        new SyncITunesAndFiles().doMain(args);
    }
    
    public void doMain(String[] args) {
        CmdLineParser parser = new CmdLineParser(this);
        
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            parser.printUsage(System.err);
            System.err.println();
            return;
        }
        
        initFieldDescriptors();
        
        IiTunes iTunes = ClassFactory.createiTunesApp();
        IITLibraryPlaylist playlist = iTunes.libraryPlaylist();
        IITTrackCollection tracks = playlist.tracks();
        int count = tracks.count();
        for (int i = 1; i <= count; i++) {
            IITFileOrCDTrack track = tracks.item(i).queryInterface(IITFileOrCDTrack.class);
            try {
                doOneTrack(track);
            } catch (Exception e) {
                System.err.format("Exception processing track: %s\n", trackToString(track));
                e.printStackTrace();
            }
        }

    }
    
    static Object[][] FIELDS = {
        {FieldKey.ALBUM, "album"},
        {FieldKey.ALBUM_ARTIST, "albumArtist"},
        {FieldKey.ARTIST, "artist"},
        {FieldKey.ALBUM_ARTIST, "albumArtist"},
        {FieldKey.COMMENT, "comment"},
        {FieldKey.COMPOSER, "composer"},
        {FieldKey.DISC_NO, "discNumber"},
        {FieldKey.DISC_TOTAL, "discCount"},
        {FieldKey.GENRE, "genre"},
        {FieldKey.TITLE, "name"},
        {FieldKey.TRACK, "trackNumber"},
    };
    
    private static class FieldDescriptor {
        final String iTunesMethodName;
        final FieldKey fieldKey;
        Method getter = null;
        Method setter = null;

        FieldDescriptor(FieldKey fieldKey, String iTunesMethodName) {
            this.fieldKey = fieldKey;
            this.iTunesMethodName = iTunesMethodName;
            
            Method[] methods = IITFileOrCDTrack.class.getMethods();

            for (Method method : methods) {
                if (method.getName().equals(iTunesMethodName)) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    if (paramTypes.length == 0) {
                        getter = method;
                    } else {
                        setter = method; 
                    }
                }
            }
            if (getter == null) {
                throw new InternalError("No getter found for: " + iTunesMethodName);
            }
            if (setter == null) {
                throw new InternalError("No setter found for: " + iTunesMethodName);
            }
            
        }
    }
    private List<FieldDescriptor> fieldDescriptors = new ArrayList<FieldDescriptor>();
    
    private void initFieldDescriptors() {
        for (Object[] pair : FIELDS) {
            fieldDescriptors.add(new FieldDescriptor((FieldKey) pair[0], (String) pair[1]));
        }
        
    }

    private void doOneTrack(IITFileOrCDTrack track) throws CannotReadException, IOException, TagException, ReadOnlyFileException, InvalidAudioFrameException {
        String location = track.location();
        //System.out.format("<%s>, <%s>, <%s>\n", track.album(), track.name(), location);
        
        if (location == null) {
            return;
        }
        AudioFile file = AudioFileIO.read(new File(location));
        Tag tag = file.getTag();
        
        String genre = tag.getFirst(FieldKey.GENRE);

        boolean modified = false;
        for (FieldDescriptor fieldDescriptor : fieldDescriptors) {
            modified |= doOneField(genre, tag, fieldDescriptor, track);    
        }
        if (modified) {
            try {
                file.commit();
            } catch (CannotWriteException e) {
                System.err.format("Error committing changes to file: %s\n", location);
            }
        }
        
        
//        showFields(tag);
    }

    @SuppressWarnings("unused")
    private void showFileTagFields(Tag tag) {
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

    private boolean doOneField(String genre, Tag tag, FieldDescriptor fieldDescriptor, IITFileOrCDTrack track) 
    {
        boolean modified = false;
        
        final String iTunesValue = getItunesValue(track, fieldDescriptor.iTunesMethodName, fieldDescriptor.getter);
        List<String> fieldValues = tag.getAll(fieldDescriptor.fieldKey);
        int fieldValueCount = fieldValues.size();

        if (fieldValueCount == 0 || isEmpty(fieldValues.get(0))) {
            if (! isEmpty(iTunesValue)) {
                modified |= handleMissingField(track, genre, tag, fieldDescriptor, iTunesValue);
            }
        } else {
            boolean found = false;
            for (String fieldValue : fieldValues) {
                if (fieldValue.equals(iTunesValue)) {
                    found = true;
                    break;
                }
            }
            if (! found) {
                if (fieldDescriptor.fieldKey == FieldKey.COMMENT /* && Character.isDigit(fieldValues.get(0).charAt(0))*/) {
                    
                } else {
                    System.err.format("No match for \"%s\" track %s; iTunes=<%s>, tags=%s\n", fieldDescriptor.iTunesMethodName, trackToString(track), iTunesValue, fieldValues);
                }
            }
        }
        return modified;
    }

    private String getItunesValue(IITFileOrCDTrack track, String iTunesMethodName, Method getter) 
    {
        final String iTunesValue;
        final Object obj;
        try {
            obj = getter.invoke(track);
        } catch (Exception e) {
            throw new InternalError(e.toString());
        }
        if (obj == null) {
            iTunesValue = null;
        }
        else if (obj instanceof String) {
            iTunesValue = (String) obj;
        } else if (obj instanceof Integer) {
            iTunesValue = Integer.toString((Integer) obj);
        } else {
            throw new InternalError(String.format("Unknown return type (%s) for: %s", obj.getClass(), iTunesMethodName));
        }
        return iTunesValue;
    }

    private boolean handleMissingField(IITFileOrCDTrack track, String genre, Tag tag, FieldDescriptor fieldDescriptor, String iTunesValue) {
        String iTunesMethodName = fieldDescriptor.iTunesMethodName;
        
        // Handle artist fields for classical and podcast tracks specially
        FieldKey fieldKey = fieldDescriptor.fieldKey;
        if ((genre.equals("Classical") || genre.equals("Podcast")) && 
                (fieldKey == FieldKey.ALBUM_ARTIST || fieldKey == FieldKey.ARTIST)) 
        {
            if (updateITunes) {
                try {
                    System.out.format("Clearing iTunes artist info in %s\n", trackToString(track));
                    fieldDescriptor.setter.invoke(track, "");
                    String comment = track.comment();
                    if (comment == null || ! comment.contains("(")) {
                        String newComment = String.format("%s(%s: %s)", 
                                                comment == null ? "" : comment + " ", 
                                                fieldKey == FieldKey.ALBUM_ARTIST ? "Album artist" : "Artist", 
                                                iTunesValue);
                        track.comment(newComment);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return false;
        }
        // Handle the composer field for non-classical tracks specially
        if (! genre.equals("Classical") && fieldKey == FieldKey.COMPOSER) {
            if (updateITunes) {
                try {
                    System.out.format("Clearing iTunes composer info in %s\n", trackToString(track));
                    fieldDescriptor.setter.invoke(track, "");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return false;
        }
        
        // Ignore disk number/total differences
        if ((fieldKey == FieldKey.DISC_NO || fieldKey == FieldKey.DISC_TOTAL || fieldKey == FieldKey.TRACK) && iTunesValue.equals("0")) {
            return false;
        }
        
        if (! updateFile) {
            return false;
        }
        
        System.err.format("Missing field \"%s\" in track %s; iTunes=<%s>\n", iTunesMethodName, trackToString(track), iTunesValue);
        try {
            tag.setField(fieldKey, iTunesValue);
            return true;
        } catch (KeyNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (FieldDataInvalidException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return false;
    }

    private boolean isEmpty(String s) {
        return s == null || s.length() == 0;
    }

    private String trackToString(IITFileOrCDTrack track) {
        return String.format("<%s, %s, %s>", track.album(), track.name(), track.location());
    }

}
