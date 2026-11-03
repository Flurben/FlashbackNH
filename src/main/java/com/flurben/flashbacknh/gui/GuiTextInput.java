package com.flurben.flashbacknh.gui;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import org.lwjgl.input.Keyboard;

/** A simple "enter a value" dialog. */
public class GuiTextInput extends GuiScreen {

    private final GuiScreen parent;
    private final String title;
    private final String initial;
    private final Consumer<String> callback;
    private GuiTextField field;

    public GuiTextInput(GuiScreen parent, String title, String initial, Consumer<String> callback) {
        this.parent = parent;
        this.title = title;
        this.initial = initial;
        this.callback = callback;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        String text = field != null ? field.getText() : initial;
        field = new GuiTextField(fontRendererObj, width / 2 - 100, height / 2 - 20, 200, 20);
        field.setMaxStringLength(100);
        field.setText(text);
        field.setFocused(true);
        buttonList.add(new GuiButton(0, width / 2 - 100, height / 2 + 10, 98, 20, "Done"));
        buttonList.add(new GuiButton(1, width / 2 + 2, height / 2 + 10, 98, 20, "Cancel"));
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == 0) callback.accept(field.getText());
        mc.displayGuiScreen(parent);
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
        } else if (key == Keyboard.KEY_RETURN) {
            callback.accept(field.getText());
            mc.displayGuiScreen(parent);
        } else {
            field.textboxKeyTyped(c, key);
        }
    }

    @Override
    protected void mouseClicked(int x, int y, int b) {
        super.mouseClicked(x, y, b);
        field.mouseClicked(x, y, b);
    }

    @Override
    public void updateScreen() {
        field.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, title, width / 2, height / 2 - 40, 0xFFFFFF);
        field.drawTextBox();
        super.drawScreen(mx, my, pt);
    }
}
