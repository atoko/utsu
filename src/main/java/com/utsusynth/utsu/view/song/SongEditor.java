package com.utsusynth.utsu.view.song;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import com.utsusynth.utsu.common.RegionBounds;
import com.utsusynth.utsu.common.data.MutateResponse;
import com.utsusynth.utsu.common.data.NoteData;
import com.utsusynth.utsu.common.data.NoteUpdateData;
import com.utsusynth.utsu.common.exception.NoteAlreadyExistsException;
import com.utsusynth.utsu.common.quantize.Quantizer;
import com.utsusynth.utsu.common.quantize.Scaler;
import com.utsusynth.utsu.common.utils.PitchUtils;
import com.utsusynth.utsu.controller.SongController.Mode;
import com.utsusynth.utsu.view.song.note.Note;
import com.utsusynth.utsu.view.song.note.NoteCallback;
import com.utsusynth.utsu.view.song.note.NoteFactory;
import com.utsusynth.utsu.view.song.note.envelope.EnvelopeCallback;
import com.utsusynth.utsu.view.song.note.pitch.PitchbendCallback;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Group;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.util.Duration;

public class SongEditor {
    private final PlaybackBarManager playbackManager;
    private final NoteFactory noteFactory;
    private final NoteMap noteMap;
    private final Scaler scaler;

    // Whether the vibrato editor is active for this song editor.
    private final BooleanProperty vibratoEditor;

    private HBox measures;
    private HBox dynamics;
    private int numMeasures;
    private SongCallback model;

    @Inject
    public SongEditor(
            PlaybackBarManager playbackManager,
            NoteFactory trackNoteFactory,
            NoteMap noteMap,
            Scaler scaler) {
        this.playbackManager = playbackManager;
        this.noteFactory = trackNoteFactory;
        this.noteMap = noteMap;
        this.scaler = scaler;

        vibratoEditor = new SimpleBooleanProperty(false);
    }

    /** Initialize track with data from the controller. Not song-specific. */
    public void initialize(SongCallback callback) {
        this.model = callback;
    }

    /** Initialize track with data for a specific song. */
    public HBox createNewTrack(List<NoteData> notes) {
        clearTrack();
        if (notes.isEmpty()) {
            return measures;
        }

        // Add as many octaves as needed.
        NoteData lastNote = notes.get(notes.size() - 1);
        setNumMeasures((lastNote.getPosition() / Quantizer.COL_WIDTH / 4) + 4);

        // Add all notes.
        NoteData prevNote = notes.get(0);
        for (NoteData note : notes) {
            Note newNote = noteFactory.createNote(note, noteCallback, vibratoEditor);
            int position = note.getPosition();
            try {
                noteMap.putNote(position, newNote);
                if (note.getEnvelope().isPresent()) {
                    noteMap.putEnvelope(
                            position,
                            note.getEnvelope().get(),
                            getEnvelopeCallback(position));
                }
                if (note.getPitchbend().isPresent()) {
                    noteMap.putPitchbend(
                            position,
                            prevNote.getPitch(),
                            note.getPitchbend().get(),
                            getPitchbendCallback(position),
                            vibratoEditor);
                }
            } catch (NoteAlreadyExistsException e) {
                // TODO: Throw an error here?
                System.out.println("UST read found two notes in the same place :(");
            }
            noteMap.addNoteElement(newNote);
            prevNote = note;
        }
        return measures;
    }

    public Group getNotesElement() {
        return noteMap.getNotesElement();
    }

    public HBox getDynamicsElement() {
        if (dynamics == null) {
            // TODO: Handle this;
            System.out.println("Dynamics element is empty!");
        }
        return dynamics;
    }

    public Group getEnvelopesElement() {
        return noteMap.getEnvelopesElement();
    }

    public Group getPitchbendsElement() {
        return noteMap.getPitchbendsElement();
    }

    public Group getPlaybackElement() {
        return playbackManager.getElement();
    }

    /** Start the playback bar animation. It will end on its own. */
    public Void startPlayback(RegionBounds rendered, Duration duration) {
        // Find exact region included in playback.
        Entry<Integer, Note> firstNote = noteMap.getFirstNote(rendered);
        Entry<Integer, Note> lastNote = noteMap.getLastNote(rendered);
        if (firstNote != null && lastNote != null) {
            int firstNoteStart = noteMap.getEnvelope(firstNote.getKey()).getStartMs();
            int renderStart = Math.min(firstNoteStart, Math.max(rendered.getMinMs(), 0));
            int renderEnd = lastNote.getKey() + lastNote.getValue().getDurationMs();
            playbackManager.startPlayback(duration, new RegionBounds(renderStart, renderEnd));
        }
        return null;
    }

    /** Attempts to pause. Does nothing if there is no ongoing playback. */
    public void pausePlayback() {
        playbackManager.pausePlayback();
    }

    /** Attempts to resume. Does nothing if there is no ongoing paused playback. */
    public void resumePlayback() {
        playbackManager.resumePlayback();
    }

    /** Manually stop any ongoing playback bar animation. Idempotent. */
    public void stopPlayback() {
        playbackManager.stopPlayback();
    }

    public RegionBounds getSelectedTrack() {
        return playbackManager.getRegionBounds();
    }

    public void selectNotes(RegionBounds region) {
        Entry<Integer, Note> firstNote = noteMap.getFirstNote(region);
        Entry<Integer, Note> lastNote = noteMap.getLastNote(region);
        if (firstNote != null && lastNote != null) {
            playbackManager.highlightTo(firstNote.getValue(), noteMap.getAllValidNotes());
            playbackManager.highlightTo(lastNote.getValue(), noteMap.getAllValidNotes());
        } else {
            // If no note highlighted, remove all highlights.
            playbackManager.highlightRegion(RegionBounds.INVALID, ImmutableList.of());
        }
    }

    public void deleteSelected() {
        Set<Integer> positionsToRemove =
                playbackManager.getHighlightedNotes().stream().filter(curNote -> curNote.isValid())
                        .map(curNote -> curNote.getAbsPositionMs()).collect(Collectors.toSet());
        RegionBounds toStandardize = removeNotes(positionsToRemove);
        if (!toStandardize.equals(RegionBounds.INVALID)) {
            refreshNotes(toStandardize.getMinMs(), toStandardize.getMaxMs());
        }

        for (Note curNote : playbackManager.getHighlightedNotes()) {
            noteMap.removeNoteElement(curNote);
        }
        playbackManager.clearHighlights();
        // TODO: If last note, remove measures until you have 4 measures + previous note.
    }

    /**
     * Removes notes from the backend song, returns RegionBounds of notes that need refreshing.
     */
    private RegionBounds removeNotes(Set<Integer> positionsToRemove) {
        if (positionsToRemove.isEmpty()) {
            return RegionBounds.INVALID; // If no valid song notes to remove, do nothing.
        }
        MutateResponse response = model.removeNotes(positionsToRemove);
        // Set backup values for all notes that were deleted, then remove them from map.
        for (NoteUpdateData updateData : response.getNotes()) {
            // Should never happen but let's check just in case.
            if (noteMap.hasNote(updateData.getPosition())) {
                noteMap.getNote(updateData.getPosition()).setBackupData(updateData);
                noteMap.removeFullNote(updateData.getPosition());
            } else {
                System.out.println("Error: Note present in backend but not in frontend!");
            }
        }

        if (response.getPrev().isPresent() && response.getNext().isPresent()) {
            return new RegionBounds(
                    response.getPrev().get().getPosition(),
                    response.getNext().get().getPosition());
        }
        return RegionBounds.INVALID;
    }

    public void refreshSelected() {
        // Refreshes all notes in currently selected region.
    }

    private void refreshNotes(int firstPosition, int lastPosition) {
        MutateResponse standardizeResponse = model.standardizeNotes(firstPosition, lastPosition);
        String prevPitch = "";
        Note prevNote = null;
        if (standardizeResponse.getPrev().isPresent()) {
            NoteUpdateData prevData = standardizeResponse.getPrev().get();
            prevNote = noteMap.getNote(prevData.getPosition());
            prevPitch = PitchUtils.rowNumToPitch(prevNote.getRow());
            noteMap.putEnvelope(
                    prevData.getPosition(),
                    prevData.getEnvelope(),
                    getEnvelopeCallback(prevData.getPosition()));
        }
        Iterator<NoteUpdateData> dataIterator = standardizeResponse.getNotes().iterator();
        NoteUpdateData curData = null;
        Note curNote = null;
        while (dataIterator.hasNext()) {
            curData = dataIterator.next();
            curNote = noteMap.getNote(curData.getPosition());
            curNote.setTrueLyric(curData.getTrueLyric());
            noteMap.putEnvelope(
                    curData.getPosition(),
                    curData.getEnvelope(),
                    getEnvelopeCallback(curData.getPosition()));
            noteMap.putPitchbend(
                    curData.getPosition(),
                    prevPitch.isEmpty() ? PitchUtils.rowNumToPitch(curNote.getRow()) : prevPitch,
                    curData.getPitchbend(),
                    getPitchbendCallback(curData.getPosition()),
                    vibratoEditor);

            if (prevNote != null) {
                prevNote.adjustForOverlap(curData.getPosition() - prevNote.getAbsPositionMs());
            }
            prevNote = curNote;
            prevPitch = PitchUtils.rowNumToPitch(curNote.getRow());
        }
        if (standardizeResponse.getNext().isPresent()) {
            NoteUpdateData nextData = standardizeResponse.getNext().get();
            Note nextNote = noteMap.getNote(nextData.getPosition());
            nextNote.setTrueLyric(nextData.getTrueLyric());
            noteMap.putEnvelope(
                    nextData.getPosition(),
                    nextData.getEnvelope(),
                    getEnvelopeCallback(nextData.getPosition()));
            noteMap.putPitchbend(
                    nextData.getPosition(),
                    prevPitch.isEmpty() ? PitchUtils.rowNumToPitch(nextNote.getRow()) : prevPitch,
                    nextData.getPitchbend(),
                    getPitchbendCallback(nextData.getPosition()),
                    vibratoEditor);
            if (curNote != null) {
                curNote.adjustForOverlap(nextData.getPosition() - curData.getPosition());
            }
        } else if (curNote != null) {
            curNote.adjustForOverlap(Integer.MAX_VALUE);
        }
    }

    public void selectivelyShowRegion(double centerPercent, double margin) {
        int measureWidthMs = 4 * Quantizer.COL_WIDTH;
        int marginMeasures = ((int) (margin / Math.round(scaler.scaleX(measureWidthMs)))) + 3;
        int centerMeasure = (int) Math.round((numMeasures - 1) * centerPercent);
        int clampedStartMeasure =
                Math.min(Math.max(centerMeasure - marginMeasures, 0), numMeasures - 1);
        int clampedEndMeasure =
                Math.min(Math.max(centerMeasure + marginMeasures, 0), numMeasures - 1);
        // Use measures to we don't have to redraw the visible region too much.
        noteMap.setVisibleRegion(
                new RegionBounds(
                        clampedStartMeasure * measureWidthMs,
                        (clampedEndMeasure + 1) * measureWidthMs));
    }

    private void clearTrack() {
        // Remove current track.
        playbackManager.clear();
        noteMap.clear();
        measures = new HBox();
        dynamics = new HBox();

        numMeasures = 0;
        setNumMeasures(4);
    }

    private void setNumMeasures(int newNumMeasures) {
        // Adjust the scrollbar to be in the same place when size of the grid changes.
        double measureWidth = 4 * Math.round(scaler.scaleX(Quantizer.COL_WIDTH));

        if (newNumMeasures < 0) {
            return;
        } else if (newNumMeasures > numMeasures) {
            for (int i = numMeasures; i < newNumMeasures; i++) {
                addMeasure();
            }
        } else if (newNumMeasures == numMeasures) {
            // Nothing needs to be done.
            return;
        } else {
            int maxWidth = (int) measureWidth * newNumMeasures;
            // Remove measures.
            measures.getChildren().removeIf((child) -> {
                return (int) Math.round(child.getLayoutX()) >= maxWidth;
            });
            // Remove dynamics columns.
            dynamics.getChildren().removeIf((child) -> {
                return (int) Math.round(child.getLayoutX()) >= maxWidth;
            });
            numMeasures = newNumMeasures;
        }
    }

    private void addMeasure() {
        GridPane newMeasure = new GridPane();
        int rowNum = 0;
        for (int octave = 7; octave > 0; octave--) {
            for (String pitch : PitchUtils.REVERSE_PITCHES) {
                // Add row to track.
                for (int colNum = 0; colNum < 4; colNum++) {
                    Pane newCell = new Pane();
                    newCell.setPrefSize(
                            Math.round(scaler.scaleX(Quantizer.COL_WIDTH)),
                            Math.round(scaler.scaleY(Quantizer.ROW_HEIGHT)));
                    newCell.getStyleClass().add("track-cell");
                    newCell.getStyleClass().add(pitch.endsWith("#") ? "black-key" : "white-key");
                    if (colNum == 0) {
                        newCell.getStyleClass().add("measure-start");
                    } else if (colNum == 3) {
                        newCell.getStyleClass().add("measure-end");
                    }

                    final int currentRowNum = rowNum;
                    final int currentColNum = colNum + (numMeasures * 4);
                    newCell.setOnMouseClicked((event) -> {
                        // Clear highlights regardless of current button or current mode.
                        playbackManager.clearHighlights();
                        if (event.getButton() != MouseButton.PRIMARY) {
                            return;
                        }
                        Mode currentMode = model.getCurrentMode();
                        if (currentMode == Mode.ADD) {
                            // Create note.
                            Note newNote = noteFactory.createDefaultNote(
                                    currentRowNum,
                                    currentColNum,
                                    noteCallback,
                                    vibratoEditor);
                            noteMap.addNoteElement(newNote);
                        }
                    });
                    newMeasure.add(newCell, colNum, rowNum);
                }
                rowNum++;
            }
        }
        measures.getChildren().add(newMeasure);

        // Add new columns to dynamics.
        GridPane newDynamics = new GridPane();
        for (int colNum = 0; colNum < 4; colNum++) {
            AnchorPane topCell = new AnchorPane();
            topCell.setPrefSize(scaler.scaleX(Quantizer.COL_WIDTH), 50);
            topCell.getStyleClass().add("dynamics-top-cell");
            if (colNum == 0) {
                topCell.getStyleClass().add("measure-start");
            }
            newDynamics.add(topCell, colNum, 0);
            AnchorPane bottomCell = new AnchorPane();
            bottomCell.setPrefSize(scaler.scaleX(Quantizer.COL_WIDTH), 50);
            bottomCell.getStyleClass().add("dynamics-bottom-cell");
            if (colNum == 0) {
                bottomCell.getStyleClass().add("measure-start");
            }
            newDynamics.add(bottomCell, colNum, 1);
        }
        dynamics.getChildren().add(newDynamics);

        numMeasures++;
    }

    private final NoteCallback noteCallback = new NoteCallback() {
        @Override
        public void highlightExclusive(Note note) {
            playbackManager.clearHighlights();
            playbackManager.highlightTo(note, noteMap.getAllValidNotes());
        }

        @Override
        public void highlightInclusive(Note note) {
            playbackManager.highlightTo(note, noteMap.getAllValidNotes());
        }

        @Override
        public boolean isExclusivelyHighlighted(Note note) {
            return playbackManager.isExclusivelyHighlighted(note);
        }

        @Override
        public void updateNote(Note note) {
            int positionMs = note.getAbsPositionMs();
            if (note.isValid()) {
                // Removes note if necessary.
                removeNotes(ImmutableSet.of(positionMs));
            }
            try {
                // Replaces note if possible.
                noteMap.putNote(positionMs, note);
                note.setValid(true);
                model.addNotes(ImmutableList.of(note.getNoteData()));
            } catch (NoteAlreadyExistsException e) {
                note.setValid(false);
            }
            // Refreshes notes regardless of whether a new one was placed.
            refreshNotes(positionMs, positionMs);
        }

        @Override
        public void moveNote(Note note, int positionDelta, int rowDelta) {
            Set<Integer> positionsToRemove = ImmutableSet.of();
            if (playbackManager.isHighlighted(note)) {
                positionsToRemove = playbackManager.getHighlightedNotes().stream()
                        .filter(curNote -> curNote.isValid())
                        .map(curNote -> curNote.getAbsPositionMs()).collect(Collectors.toSet());
            } else if (note.isValid()) {
                positionsToRemove = ImmutableSet.of(note.getAbsPositionMs());
            }
            RegionBounds toStandardize = removeNotes(positionsToRemove);

            List<Note> notes;
            if (playbackManager.isHighlighted(note)) {
                notes = playbackManager.getHighlightedNotes().stream().sorted(
                        (note1, note2) -> Integer
                                .compare(note1.getAbsPositionMs(), note2.getAbsPositionMs()))
                        .collect(Collectors.toList());
            } else {
                notes = ImmutableList.of(note);
            }
            LinkedList<NoteData> toAdd = new LinkedList<>();
            for (Note curNote : notes) {
                curNote.moveNoteElement(positionDelta, rowDelta);
                curNote.setValid(true);
                try {
                    noteMap.putNote(curNote.getAbsPositionMs(), curNote);
                } catch (NoteAlreadyExistsException e) {
                    curNote.setValid(false);
                    continue;
                }
                toAdd.add(curNote.getNoteData());
            }
            // Standardize and return early if nothing needs to be added.
            if (toAdd.isEmpty()) {
                if (!toStandardize.equals(RegionBounds.INVALID)) {
                    refreshNotes(toStandardize.getMinMs(), toStandardize.getMaxMs());
                }
                return;
            }
            model.addNotes(toAdd);

            RegionBounds addRegion =
                    new RegionBounds(toAdd.getFirst().getPosition(), toAdd.getLast().getPosition());
            if (toStandardize.equals(RegionBounds.INVALID)) {
                toStandardize = addRegion;
            } else {
                toStandardize = toStandardize.mergeWith(addRegion);
            }
            refreshNotes(toStandardize.getMinMs(), toStandardize.getMaxMs());

            // If new last note has been added, update track size.
            if (toStandardize.getMaxMs() == addRegion.getMaxMs()) {
                setNumMeasures((addRegion.getMaxMs() / Quantizer.COL_WIDTH / 4) + 4);
            }
        }

        @Override
        public void deleteNote(Note note) {
            Set<Integer> positionsToRemove = ImmutableSet.of();
            if (playbackManager.isHighlighted(note)) {
                positionsToRemove = playbackManager.getHighlightedNotes().stream()
                        .filter(curNote -> curNote.isValid())
                        .map(curNote -> curNote.getAbsPositionMs()).collect(Collectors.toSet());
            } else if (note.isValid()) {
                positionsToRemove = ImmutableSet.of(note.getAbsPositionMs());
            }
            RegionBounds toStandardize = removeNotes(positionsToRemove);
            if (!toStandardize.equals(RegionBounds.INVALID)) {
                refreshNotes(toStandardize.getMinMs(), toStandardize.getMaxMs());
            }

            if (playbackManager.isHighlighted(note)) {
                for (Note curNote : playbackManager.getHighlightedNotes()) {
                    noteMap.removeNoteElement(curNote);
                }
                playbackManager.clearHighlights();
            } else {
                noteMap.removeNoteElement(note);
            }
            // TODO: If last note, remove measures until you have 4 measures + previous note.
        }

        @Override
        public Mode getCurrentMode() {
            return model.getCurrentMode();
        }

        @Override
        public boolean hasVibrato(int position) {
            if (noteMap.hasPitchbend(position)) {
                return noteMap.getPitchbend(position).hasVibrato();
            }
            return false;
        }

        @Override
        public void setHasVibrato(int position, boolean hasVibrato) {
            if (noteMap.hasPitchbend(position)) {
                noteMap.getPitchbend(position).setHasVibrato(hasVibrato);
            }
        }

        @Override
        public void openNoteProperties(Note note) {
            if (playbackManager.isHighlighted(note)) {
                model.openNoteProperties(playbackManager.getRegionBounds());
            } else {
                // Open on current note if current note is not highlighted.
                model.openNoteProperties(note.getValidBounds());
            }
        }
    };

    private EnvelopeCallback getEnvelopeCallback(final int position) {
        return new EnvelopeCallback() {
            @Override
            public void modifySongEnvelope() {
                Note toModify = noteMap.getNote(position);
                NoteData mutation = new NoteData(
                        toModify.getAbsPositionMs(),
                        toModify.getDurationMs(),
                        PitchUtils.rowNumToPitch(toModify.getRow()),
                        toModify.getLyric(),
                        noteMap.getEnvelope(position).getData());
                model.modifyNote(mutation);
            }
        };
    }

    private PitchbendCallback getPitchbendCallback(final int position) {
        return new PitchbendCallback() {
            @Override
            public void modifySongPitchbend() {
                Note toModify = noteMap.getNote(position);
                NoteData mutation = new NoteData(
                        position,
                        toModify.getDurationMs(),
                        PitchUtils.rowNumToPitch(toModify.getRow()),
                        toModify.getLyric(),
                        noteMap.getPitchbend(position).getData(position));
                model.modifyNote(mutation);
            }
        };
    }
}
