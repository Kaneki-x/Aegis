package com.beemdevelopment.aegis.ime;

import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.beemdevelopment.aegis.Preferences;
import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.VibrationPatterns;
import com.beemdevelopment.aegis.helpers.CodeFormatHelper;
import com.beemdevelopment.aegis.helpers.ThemeHelper;
import com.beemdevelopment.aegis.helpers.VibrationHelper;
import com.beemdevelopment.aegis.otp.HotpInfo;
import com.beemdevelopment.aegis.otp.OtpInfo;
import com.beemdevelopment.aegis.otp.OtpInfoException;
import com.beemdevelopment.aegis.ui.AuthActivity;
import com.beemdevelopment.aegis.ui.MainActivity;
import com.beemdevelopment.aegis.vault.VaultEntry;
import com.beemdevelopment.aegis.vault.VaultManager;
import com.beemdevelopment.aegis.vault.VaultRepositoryException;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * A keyboard that doesn't type: it shows the entries of the vault and inserts the code of
 * the entry the user taps into the focused text field, then switches back to the keyboard
 * that was active before.
 */
@AndroidEntryPoint
public class AegisInputMethodService extends InputMethodService
        implements VaultManager.LockListener, ImeEntryAdapter.Listener {
    @Inject
    VaultManager _vaultManager;

    @Inject
    Preferences _prefs;

    private VibrationHelper _vibrationHelper;

    private String _themeKey;
    private View _root;
    private TextView _headerHint;
    private RecyclerView _list;
    private View _stateView;
    private ImageView _stateIcon;
    private TextView _stateText;
    private MaterialButton _stateButton;
    private ChipGroup _chips;
    private ImeEntryAdapter _adapter;

    private String _currentPackage;
    private String _chipsKey;

    @Override
    public void onCreate() {
        super.onCreate();
        _vibrationHelper = new VibrationHelper(this);
        _vaultManager.registerLockListener(this);
    }

    @Override
    public void onDestroy() {
        _vaultManager.unregisterLockListener(this);
        _vaultManager.setInputMethodVisible(false);
        super.onDestroy();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public View onCreateInputView() {
        Context context = ThemeHelper.createThemedContext(this, _prefs);
        _themeKey = ThemeHelper.getThemeKey(this, _prefs);

        _root = LayoutInflater.from(context).inflate(R.layout.ime_picker, null);
        _headerHint = _root.findViewById(R.id.header_hint);
        _list = _root.findViewById(R.id.list);
        _stateView = _root.findViewById(R.id.state_view);
        _stateIcon = _root.findViewById(R.id.state_icon);
        _stateText = _root.findViewById(R.id.state_text);
        _stateButton = _root.findViewById(R.id.state_button);
        _chips = _root.findViewById(R.id.chips);
        _chipsKey = null;

        _adapter = new ImeEntryAdapter(Glide.with(getApplicationContext()), this);
        _list.setLayoutManager(new LinearLayoutManager(context));
        _list.setAdapter(_adapter);

        _root.findViewById(R.id.btn_open_app).setOnClickListener(v -> openApp());
        _root.findViewById(R.id.btn_switch_keyboard).setOnClickListener(v -> switchToPreviousKeyboard());
        _chips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                return;
            }
            Chip chip = group.findViewById(checkedIds.get(0));
            String filter = chip != null ? (String) chip.getTag() : null;
            if (!Objects.equals(filter, _adapter.getFilter())) {
                _adapter.setFilter(filter);
                _list.scrollToPosition(0);
                updateEmptyState();
            }
        });

        // The IME framework measures the input view with WRAP_CONTENT, which would let
        // the list grow to its full length. Pin the panel to a keyboard-like height and
        // keep it clear of the (gesture) navigation bar.
        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setBackgroundColor(MaterialColors.getColor(_root, com.google.android.material.R.attr.colorSurfaceContainerLow));
        int height = getResources().getDimensionPixelSize(R.dimen.ime_panel_height);
        wrapper.addView(_root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        ViewCompat.setOnApplyWindowInsetsListener(wrapper, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(0, 0, 0, bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        return wrapper;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);

        if (_root == null || !Objects.equals(_themeKey, ThemeHelper.getThemeKey(this, _prefs))) {
            setInputView(onCreateInputView());
        }

        updateSecureFlag();

        String packageName = info != null ? info.packageName : null;
        if (!restarting || !Objects.equals(packageName, _currentPackage)) {
            _currentPackage = packageName;
            _adapter.setFilter(null);
            _chipsKey = null;
        }

        refresh();
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        _vaultManager.setInputMethodVisible(true);
        if (_root != null) {
            // the vault may have been unlocked through AuthActivity while we were hidden
            refresh();
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        _vaultManager.setInputMethodVisible(false);
    }

    @Override
    public void onLocked(boolean userInitiated) {
        if (_root != null) {
            refresh();
        }
    }

    private void updateSecureFlag() {
        Window window = getWindow() != null ? getWindow().getWindow() : null;
        if (window == null) {
            return;
        }

        if (_prefs.isSecureScreenEnabled()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void refresh() {
        if (_currentPackage != null && _currentPackage.equals(getPackageName())) {
            // We're attached to one of our own text fields, most likely the password field
            // of AuthActivity. Our codes are of no use there, but a real keyboard is.
            showState(R.drawable.ic_outline_keyboard_24, R.string.ime_own_app_hint, R.string.ime_switch_keyboard, v -> switchToPreviousKeyboard());
            return;
        }

        if (_vaultManager.isVaultInitNeeded()) {
            showState(R.drawable.ic_cue_mark, R.string.ime_setup_required, R.string.ime_open_app, v -> openApp());
            return;
        }

        if (!_vaultManager.loadIfUnencrypted()) {
            showState(R.drawable.ic_outline_lock_24, R.string.ime_vault_locked, R.string.unlock, v -> unlock());
            return;
        }

        _adapter.setCodeGrouping(_prefs.getCodeGroupSize());
        _adapter.setHideCodes(_prefs.isTapToRevealEnabled());
        _adapter.setShowIcons(_prefs.isIconVisible());

        Set<String> callerTokens = _prefs.isImeSuggestByAppEnabled()
                ? ImeEntryRanker.getCallerTokens(this, _currentPackage)
                : java.util.Collections.emptySet();
        List<ImeEntryRanker.Ranked> ranked = ImeEntryRanker.rank(
                _vaultManager.getVault().getEntries(), callerTokens, _prefs.getUsageCounts());
        _adapter.setEntries(ranked);

        boolean anySuggested = !ranked.isEmpty() && ranked.get(0).isSuggested();
        _headerHint.setText(anySuggested ? getCallerLabel() : "");

        buildChips();
        showList();
        updateEmptyState();
    }

    private String getCallerLabel() {
        if (_currentPackage == null) {
            return "";
        }

        try {
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(_currentPackage, 0));
            return getString(R.string.ime_hint_suggested_for, label);
        } catch (Exception e) {
            return "";
        }
    }

    private void buildChips() {
        List<String> initials = _adapter.getInitials();
        boolean hasFavorites = _adapter.hasFavorites();
        String key = hasFavorites + "/" + initials;
        if (key.equals(_chipsKey)) {
            return;
        }
        _chipsKey = key;

        _chips.removeAllViews();
        addChip(null, getString(R.string.all), true);
        if (hasFavorites) {
            addChip(ImeEntryAdapter.FILTER_FAVORITES, ImeEntryAdapter.FILTER_FAVORITES, false)
                    .setContentDescription(getString(R.string.ime_filter_favorites));
        }
        for (String initial : initials) {
            addChip(initial, initial, false);
        }
    }

    private Chip addChip(@Nullable String filter, String text, boolean checked) {
        Chip chip = (Chip) LayoutInflater.from(_chips.getContext()).inflate(R.layout.ime_chip, _chips, false);
        chip.setId(View.generateViewId());
        chip.setTag(filter);
        chip.setText(text);
        _chips.addView(chip);
        if (checked) {
            _chips.check(chip.getId());
        }
        return chip;
    }

    private void showList() {
        _stateView.setVisibility(View.GONE);
        _list.setVisibility(View.VISIBLE);
        _chips.setVisibility(View.VISIBLE);
    }

    private void updateEmptyState() {
        if (_adapter.getItemCount() > 0) {
            _stateView.setVisibility(View.GONE);
            _list.setVisibility(View.VISIBLE);
            return;
        }

        _list.setVisibility(View.GONE);
        _stateView.setVisibility(View.VISIBLE);
        _stateIcon.setImageResource(R.drawable.ic_cue_mark);
        _stateText.setText(_adapter.hasEntries() ? R.string.ime_no_matches : R.string.ime_no_entries);
        _stateButton.setVisibility(View.GONE);
    }

    private void showState(int iconRes, int textRes, int buttonRes, View.OnClickListener listener) {
        _list.setVisibility(View.GONE);
        _chips.setVisibility(View.GONE);
        _headerHint.setText("");
        _stateView.setVisibility(View.VISIBLE);
        _stateIcon.setImageResource(iconRes);
        _stateText.setText(textRes);
        _stateButton.setVisibility(View.VISIBLE);
        _stateButton.setText(buttonRes);
        _stateButton.setOnClickListener(listener);
    }

    private void unlock() {
        startActivity(AuthActivity.createExternalIntent(this));
    }

    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    public void onEntryClick(VaultEntry entry) {
        if (!_vaultManager.isVaultLoaded()) {
            refresh();
            return;
        }

        OtpInfo info = entry.getInfo();
        String code;
        try {
            code = info.getOtp();
        } catch (OtpInfoException e) {
            Toast.makeText(this, R.string.error_all_caps, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!insertCode(code)) {
            return;
        }

        recordUsage(entry);
        _vibrationHelper.vibratePattern(this, VibrationPatterns.REFRESH_CODE);

        if (info instanceof HotpInfo) {
            // the code has been consumed, move on to the next counter value like the
            // refresh button in the main entry list does
            try {
                ((HotpInfo) info).incrementCounter();
                _vaultManager.saveAndBackup();
            } catch (OtpInfoException | VaultRepositoryException e) {
                e.printStackTrace();
                Toast.makeText(this, R.string.saving_error, Toast.LENGTH_LONG).show();
            }
        }

        if (_prefs.isImeSwitchBackEnabled()) {
            switchToPreviousKeyboard();
        }
    }

    /**
     * Inserts the code into the focused field. Numeric codes are sent as individual key
     * presses, so that forms with one box per digit (which move the focus after every
     * digit) receive all of them. Other codes are committed as text.
     */
    private boolean insertCode(String code) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return false;
        }

        if (CodeFormatHelper.isNumeric(code) && !_prefs.isImeInsertAsTextEnabled()) {
            for (int i = 0; i < code.length(); i++) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_0 + (code.charAt(i) - '0'));
            }
        } else {
            ic.commitText(code, 1);
        }

        return true;
    }

    private void recordUsage(VaultEntry entry) {
        UUID uuid = entry.getUUID();

        Map<UUID, Integer> usageCounts = _prefs.getUsageCounts();
        Integer count = usageCounts.get(uuid);
        usageCounts.put(uuid, count != null ? count + 1 : 1);
        _prefs.setUsageCount(usageCounts);

        Map<UUID, Long> timestamps = _prefs.getLastUsedTimestamps();
        timestamps.put(uuid, new Date().getTime());
        _prefs.setLastUsedTimestamps(timestamps);
    }

    private void switchToPreviousKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!switchToPreviousInputMethod()) {
                requestHideSelf(0);
            }
            return;
        }

        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        Window window = getWindow() != null ? getWindow().getWindow() : null;
        IBinder token = window != null ? window.getAttributes().token : null;
        if (imm == null || token == null || !imm.switchToLastInputMethod(token)) {
            requestHideSelf(0);
        }
    }
}
