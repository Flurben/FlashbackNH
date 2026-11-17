package com.flurben.flashbacknh.editor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Undo and redo for the editor: earlier states of what the user edits, kept as JSON. A change is taken in once it is
 * settled, so that dragging a keyframe or a slider is one step and not one per frame.
 */
public final class EditHistory {

    private static final int LIMIT = 200;

    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private String committed;
    private boolean pending;

    /** Starts over from this state. */
    public void reset(EditorState state) {
        undo.clear();
        redo.clear();
        committed = state.historyJson();
        pending = false;
    }

    /** Something may have changed; {@link #settle} finds out what. */
    public void changed() {
        pending = true;
    }

    /** Takes in a change, for when no drag or typing is going on. */
    public void settle(EditorState state) {
        if (!pending) return;
        pending = false;
        String now = state.historyJson();
        if (committed == null) {
            committed = now;
            return;
        }
        if (now.equals(committed)) return;
        undo.push(committed);
        while (undo.size() > LIMIT) undo.removeLast();
        redo.clear();
        committed = now;
    }

    /** Steps that can be undone and redone, for tests. */
    public String describe() {
        return undo.size() + " undo, " + redo.size() + " redo" + (pending ? ", pending" : "");
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Goes back one step. False when there is nothing to undo. */
    public boolean undo(EditorState state) {
        settle(state);
        if (undo.isEmpty()) return false;
        redo.push(committed);
        committed = undo.pop();
        state.restoreHistory(committed);
        return true;
    }

    /** Goes forward one undone step. False when there is nothing to redo. */
    public boolean redo(EditorState state) {
        settle(state);
        if (redo.isEmpty()) return false;
        undo.push(committed);
        committed = redo.pop();
        state.restoreHistory(committed);
        return true;
    }
}
