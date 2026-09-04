package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.R;

public class TranslationAction extends TwoStateAction {
    public TranslationAction(Context context) {
        super(context, R.id.action_translation, R.drawable.action_translation);

        String label = context.getString(R.string.action_translation);
        String[] labels = new String[2];
        labels[INDEX_OFF] = label;
        labels[INDEX_ON] = label;
        setLabels(labels);
    }
}
