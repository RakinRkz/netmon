package com.opendroid.netmon;

import android.view.View;

/** One bottom-navigation screen. Views are built once; onShow/onHide follow visibility and resume/pause. */
abstract class Page {
    final MainActivity act;
    private View view;

    Page(MainActivity act) {
        this.act = act;
    }

    final View view() {
        if (view == null) view = build();
        return view;
    }

    abstract View build();

    void onShow() {}

    void onHide() {}
}
