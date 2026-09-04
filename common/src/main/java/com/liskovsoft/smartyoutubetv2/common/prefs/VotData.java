package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.sharedutils.helpers.Helpers;

public class VotData {
    private static final String VOT_DATA = "vot_data";
    @SuppressLint("StaticFieldLeak")
    private static VotData sInstance;
    private final AppPrefs mAppPrefs;
    private float mOriginalVolume;
    private String mTargetLanguage;

    private VotData(Context context) {
        mAppPrefs = AppPrefs.instance(context);
        restoreState();
    }

    public static VotData instance(Context context) {
        if (sInstance == null) {
            sInstance = new VotData(context.getApplicationContext());
        }

        return sInstance;
    }

    public float getOriginalVolume() {
        return mOriginalVolume;
    }

    public void setOriginalVolume(float volume) {
        mOriginalVolume = volume;
        persistState();
    }

    public String getTargetLanguage() {
        return mTargetLanguage;
    }

    public void setTargetLanguage(String language) {
        mTargetLanguage = language;
        persistState();
    }

    private void restoreState() {
        String[] split = Helpers.splitData(mAppPrefs.getData(VOT_DATA));

        mOriginalVolume = Helpers.parseFloat(split, 0, 0.15f);
        mTargetLanguage = Helpers.parseStr(split, 1, "ru");
    }

    private void persistState() {
        mAppPrefs.setData(VOT_DATA, Helpers.mergeData(mOriginalVolume, mTargetLanguage));
    }
}
