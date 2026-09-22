package com.rusefi.m749;

import com.rusefi.ui.UIContext;
import com.rusefi.ui.plugins.ConsoleTabProvider;

import javax.swing.JComponent;

public final class M749TabProvider implements ConsoleTabProvider {
    @Override
    public String getTitle() {
        return "M74.9";
    }

    @Override
    public JComponent createTab(UIContext uiContext) {
        return new M749Panel();
    }
}
