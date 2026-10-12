package com.blueledger.app.fixture;

import android.inputmethodservice.InputMethodService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native test IME runs independently of the instrumented app and needs no shared runtime. */
public class ComponentTestIme extends InputMethodService {
    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public View onCreateInputView() {
        LinearLayout keyboard = new LinearLayout(this);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        keyboard.setBackgroundColor(0xFFE4EAF4);
        int rowHeight = Math.round(48 * getResources().getDisplayMetrics().density);
        TextView label = new TextView(this);
        label.setText("ADB 测试输入法");
        label.setTextSize(14);
        label.setGravity(Gravity.CENTER);
        keyboard.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
        String[][] rows = {{"1", "2", "3", "4", "5"}, {"6", "7", "8", "9", "0"}, {".", "删除", "收起"}};
        for (String[] keys : rows) {
            LinearLayout row = new LinearLayout(this);
            for (String key : keys) {
                Button button = new Button(this);
                button.setText(key);
                button.setTextSize(14);
                button.setOnClickListener(view -> {
                    if ("收起".equals(key)) requestHideSelf(0);
                    else if (getCurrentInputConnection() != null) {
                        if ("删除".equals(key)) getCurrentInputConnection().deleteSurroundingText(1, 0);
                        else getCurrentInputConnection().commitText(key, 1);
                    }
                });
                row.addView(button, new LinearLayout.LayoutParams(0, rowHeight, 1));
            }
            keyboard.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
        }
        return keyboard;
    }
}
