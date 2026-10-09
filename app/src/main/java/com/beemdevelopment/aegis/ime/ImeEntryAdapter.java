package com.beemdevelopment.aegis.ime;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import com.beemdevelopment.aegis.Preferences;
import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.helpers.CodeFormatHelper;
import com.beemdevelopment.aegis.otp.OtpInfo;
import com.beemdevelopment.aegis.otp.OtpInfoException;
import com.beemdevelopment.aegis.otp.TotpInfo;
import com.beemdevelopment.aegis.ui.glide.GlideHelper;
import com.beemdevelopment.aegis.vault.VaultEntry;
import com.bumptech.glide.RequestManager;
import com.google.android.material.color.MaterialColors;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

public class ImeEntryAdapter extends RecyclerView.Adapter<ImeEntryAdapter.Holder> {
    /**
     * Filter key that only shows favorites.
     */
    public static final String FILTER_FAVORITES = "★";

    private final RequestManager _glide;
    private final Listener _listener;

    private List<ImeEntryRanker.Ranked> _all = new ArrayList<>();
    private final List<ImeEntryRanker.Ranked> _visible = new ArrayList<>();

    private String _filter;
    private Preferences.CodeGrouping _codeGrouping = Preferences.CodeGrouping.GROUPING_THREES;
    private boolean _hideCodes;
    private boolean _showIcons = true;

    public ImeEntryAdapter(RequestManager glide, Listener listener) {
        _glide = glide;
        _listener = listener;
    }

    public void setCodeGrouping(Preferences.CodeGrouping grouping) {
        _codeGrouping = grouping;
    }

    public void setHideCodes(boolean hide) {
        _hideCodes = hide;
    }

    public void setShowIcons(boolean show) {
        _showIcons = show;
    }

    public void setEntries(List<ImeEntryRanker.Ranked> entries) {
        _all = entries;
        applyFilter();
    }

    /**
     * Sets the filter: null shows everything, FILTER_FAVORITES shows favorites only and
     * any other value is interpreted as the first letter of the issuer (or name).
     */
    public void setFilter(@Nullable String filter) {
        _filter = filter;
        applyFilter();
    }

    @Nullable
    public String getFilter() {
        return _filter;
    }

    public boolean hasEntries() {
        return !_all.isEmpty();
    }

    public boolean hasFavorites() {
        for (ImeEntryRanker.Ranked ranked : _all) {
            if (ranked.getEntry().isFavorite()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the distinct, sorted first letters of all entries, for building the filter chips.
     */
    public List<String> getInitials() {
        TreeSet<String> initials = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ImeEntryRanker.Ranked ranked : _all) {
            String initial = getInitial(ranked.getEntry());
            if (initial != null) {
                initials.add(initial);
            }
        }
        return new ArrayList<>(initials);
    }

    private void applyFilter() {
        _visible.clear();
        for (ImeEntryRanker.Ranked ranked : _all) {
            if (_filter == null
                    || (_filter.equals(FILTER_FAVORITES) && ranked.getEntry().isFavorite())
                    || _filter.equalsIgnoreCase(getInitial(ranked.getEntry()))) {
                _visible.add(ranked);
            }
        }
        notifyDataSetChanged();
    }

    @Nullable
    private static String getInitial(VaultEntry entry) {
        String text = entry.getIssuer().trim();
        if (text.isEmpty()) {
            text = entry.getName().trim();
        }
        if (text.isEmpty()) {
            return null;
        }

        BreakIterator iter = BreakIterator.getCharacterInstance();
        iter.setText(text);
        int start = iter.first(), end = iter.next();
        if (end == BreakIterator.DONE) {
            return null;
        }

        return text.substring(start, end).toUpperCase(Locale.getDefault());
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.ime_entry_item, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(_visible.get(position), position == 0);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        holder.recycle();
    }

    @Override
    public int getItemCount() {
        return _visible.size();
    }

    class Holder extends RecyclerView.ViewHolder {
        private final View _favoriteIndicator;
        private final ImageView _icon;
        private final TextView _issuer;
        private final TextView _name;
        private final TextView _suggested;
        private final TextView _code;
        private final CountdownRingView _ring;
        private final ImageView _hotpIcon;

        private VaultEntry _entry;
        private boolean _hero;

        Holder(View view) {
            super(view);
            _favoriteIndicator = view.findViewById(R.id.favorite_indicator);
            _icon = view.findViewById(R.id.icon);
            _issuer = view.findViewById(R.id.issuer);
            _name = view.findViewById(R.id.name);
            _suggested = view.findViewById(R.id.suggested);
            _code = view.findViewById(R.id.code);
            _ring = view.findViewById(R.id.ring);
            _hotpIcon = view.findViewById(R.id.hotp_icon);

            _ring.setListener(this::refreshCode);
            view.setOnClickListener(v -> {
                if (_entry != null) {
                    _listener.onEntryClick(_entry);
                }
            });
        }

        void bind(ImeEntryRanker.Ranked ranked, boolean first) {
            _entry = ranked.getEntry();

            String issuer = _entry.getIssuer();
            String name = _entry.getName();
            if (issuer.isEmpty()) {
                issuer = name;
                name = "";
            }
            _issuer.setText(issuer);
            _name.setText(name);
            _name.setVisibility(name.isEmpty() ? View.GONE : View.VISIBLE);
            // the top suggestion is the hero card, further suggestions just get a pill
            _hero = ranked.isSuggested() && first;
            _suggested.setVisibility(ranked.isSuggested() && !_hero ? View.VISIBLE : View.GONE);
            _favoriteIndicator.setVisibility(_entry.isFavorite() ? View.VISIBLE : View.GONE);
            applyHero();

            OtpInfo info = _entry.getInfo();
            if (info instanceof TotpInfo) {
                _ring.setPeriod(((TotpInfo) info).getPeriod());
                _ring.setVisibility(View.VISIBLE);
                _hotpIcon.setVisibility(View.GONE);
            } else {
                _ring.setVisibility(View.GONE);
                _hotpIcon.setVisibility(View.VISIBLE);
            }

            if (_showIcons) {
                _icon.setVisibility(View.VISIBLE);
                GlideHelper.loadEntryIcon(_glide, _entry, _icon);
            } else {
                _glide.clear(_icon);
                _icon.setVisibility(View.GONE);
            }

            refreshCode();
        }

        void refreshCode() {
            if (_entry == null) {
                return;
            }

            String code;
            try {
                code = CodeFormatHelper.format(_entry.getInfo().getOtp(), _entry.getInfo(), _codeGrouping);
            } catch (OtpInfoException e) {
                code = itemView.getResources().getString(R.string.error_all_caps);
            }

            _code.setText(_hideCodes ? CodeFormatHelper.hide(code) : code);
            if (_hero) {
                _code.setTextColor(_hideCodes ? 0x99FFFFFF : 0xFFFFFFFF);
            } else {
                _code.setTextColor(MaterialColors.getColor(_code, _hideCodes ? R.attr.colorCodeHidden : R.attr.colorCode));
            }
        }

        /**
         * The suggested entry is the one tinted card: brand gradient with white text.
         */
        private void applyHero() {
            Context context = itemView.getContext();
            itemView.setBackground(AppCompatResources.getDrawable(context,
                    _hero ? R.drawable.cue_card_background_hero : R.drawable.cue_card_background));
            int onSurface = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurface);
            int onSurfaceVariant = MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurfaceVariant);
            _issuer.setTextColor(_hero ? 0xFFFFFFFF : onSurface);
            _name.setTextColor(_hero ? 0xCCFFFFFF : onSurfaceVariant);
            _suggested.setTextColor(_hero ? 0xFFFFFFFF : MaterialColors.getColor(itemView, androidx.appcompat.R.attr.colorPrimary));
            _suggested.setBackgroundTintList(_hero ? ColorStateList.valueOf(0x33FFFFFF) : null);
            _ring.setHero(_hero);
            _hotpIcon.setImageTintList(ColorStateList.valueOf(_hero ? 0xFFFFFFFF : onSurfaceVariant));
        }

        void recycle() {
            _entry = null;
            _glide.clear(_icon);
        }
    }

    public interface Listener {
        void onEntryClick(VaultEntry entry);
    }
}
