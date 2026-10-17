package com.qingyu.weibopast.adapters;

import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.qingyu.weibopast.R;
import com.qingyu.weibopast.beans.User;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class StatusAdapter extends RecyclerView.Adapter<StatusAdapter.ViewHolder> {

    private List<User.Status> mStatuses;
    private Context mContext;
    private OnItemClickListener mListener;
    private String userName = "";
    private String userAvatar = "";

    public interface OnItemClickListener {
        void onItemClick(View view, int position, User.Status status);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.mListener = listener;
    }

    public StatusAdapter(Context context) {
        this.mContext = context;
        this.mStatuses = new ArrayList<>();
    }

    public void setUserInfo(String name, String avatarUrl) {
        this.userName = name;
        this.userAvatar = avatarUrl;
        notifyDataSetChanged();
    }

    public void setStatuses(List<User.Status> statuses) {
        mStatuses.clear();
        if (statuses != null) {
            mStatuses.addAll(statuses);
        }
        notifyDataSetChanged();
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(mContext).inflate(R.layout.item_status, parent, false);
        final ViewHolder holder = new ViewHolder(view);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) {
                    int position = holder.getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        mListener.onItemClick(v, position, mStatuses.get(position));
                    }
                }
            }
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        User.Status status = mStatuses.get(position);

        // User info
        holder.tvUserName.setText(userName.isEmpty() ? "Me" : userName);
        if (!userAvatar.isEmpty()) {
            Picasso.with(mContext)
                    .load(userAvatar)
                    .resize(80, 80)
                    .centerCrop()
                    .into(holder.ivAvatar);
        }

        // Status text
        holder.tvText.setText(status.getText());
        holder.tvCreatedAt.setText(status.getCreated_at());

        // Parse source HTML tags to get plain text
        String source = status.getSource();
        if (source != null) {
            String plainSource = android.text.Html.fromHtml(source).toString();
            holder.tvSource.setText(plainSource.length() > 20 ? plainSource.substring(0, 20) + "..." : plainSource);
        } else {
            holder.tvSource.setText("");
        }

        // Stats
        holder.tvRepostsCount.setText("\u21BB " + formatCount(status.getReposts_count()));
        holder.tvCommentsCount.setText("\uD83D\uDCAC " + formatCount(status.getComments_count()));
        holder.tvAttitudesCount.setText("\u2764 " + formatCount(status.getAttitudes_count()));

        // Handle images - build grid layout
        holder.layoutImages.removeAllViews();
        List<User.Status.PicUrls> picUrls = status.getPic_urls();

        if (picUrls != null && !picUrls.isEmpty()) {
            holder.layoutImages.setVisibility(View.VISIBLE);

            if (picUrls.size() == 1) {
                // Single image - large
                User.Status.PicUrls picUrl = picUrls.get(0);
                if (picUrl != null && picUrl.getThumbnail_pic() != null && !picUrl.getThumbnail_pic().isEmpty()) {
                    int imageSize = dpToPx(240);
                    ImageView imageView = createImageView(holder.layoutImages, imageSize, 0);
                    Picasso.with(mContext)
                            .load(picUrl.getThumbnail_pic().replace("/thumbnail/", "/bmiddle/"))
                            .resize(imageSize, imageSize)
                            .centerCrop()
                            .into(imageView);
                }
            } else {
                // Multiple images - 3 per row
                int imageSize = dpToPx(110);
                int margin = dpToPx(4);
                LinearLayout currentRow = null;
                for (int i = 0; i < picUrls.size() && i < 9; i++) {
                    if (i % 3 == 0) {
                        currentRow = new LinearLayout(mContext);
                        currentRow.setOrientation(LinearLayout.HORIZONTAL);
                        holder.layoutImages.addView(currentRow);
                    }
                    User.Status.PicUrls picUrl = picUrls.get(i);
                    if (picUrl != null && picUrl.getThumbnail_pic() != null && !picUrl.getThumbnail_pic().isEmpty()) {
                        ImageView imageView = new ImageView(mContext);
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(imageSize, imageSize);
                        if (i % 3 < 2) params.setMargins(0, 0, margin, 0);
                        if (i / 3 > 0) params.topMargin = margin;
                        imageView.setLayoutParams(params);
                        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        currentRow.addView(imageView);
                        Picasso.with(mContext)
                                .load(picUrl.getThumbnail_pic().replace("/thumbnail/", "/bmiddle/"))
                                .resize(imageSize, imageSize)
                                .centerCrop()
                                .into(imageView);
                    }
                }
            }
        } else if (status.getThumbnail_pic() != null && !status.getThumbnail_pic().isEmpty()) {
            holder.layoutImages.setVisibility(View.VISIBLE);
            int imageSize = dpToPx(240);
            ImageView imageView = createImageView(holder.layoutImages, imageSize, 0);
            String url = (status.getBmiddle_pic() != null && !status.getBmiddle_pic().isEmpty())
                    ? status.getBmiddle_pic() : status.getThumbnail_pic();
            Picasso.with(mContext)
                    .load(url)
                    .resize(imageSize, imageSize)
                    .centerCrop()
                    .into(imageView);
        } else {
            holder.layoutImages.setVisibility(View.GONE);
        }
    }

    private ImageView createImageView(LinearLayout parent, int size, int margin) {
        ImageView imageView = new ImageView(mContext);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        if (margin > 0) {
            params.setMargins(0, 0, margin, 0);
        }
        imageView.setLayoutParams(params);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        parent.addView(imageView);
        return imageView;
    }

    private int dpToPx(int dp) {
        float density = mContext.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    private String formatCount(int count) {
        if (count >= 10000) {
            return String.format(Locale.ENGLISH, "%.1fk", count / 10000.0);
        } else if (count > 0) {
            return String.valueOf(count);
        } else {
            return "0";
        }
    }

    @Override
    public int getItemCount() {
        return mStatuses != null ? mStatuses.size() : 0;
    }

    public User.Status getItem(int position) {
        if (position >= 0 && position < mStatuses.size()) {
            return mStatuses.get(position);
        }
        return null;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        ImageView ivAvatar;
        TextView tvUserName;
        TextView tvText;
        TextView tvCreatedAt;
        TextView tvSource;
        TextView tvRepostsCount;
        TextView tvCommentsCount;
        TextView tvAttitudesCount;
        LinearLayout layoutImages;

        ViewHolder(View itemView) {
            super(itemView);
            ivAvatar = (ImageView) itemView.findViewById(R.id.iv_avatar);
            tvUserName = (TextView) itemView.findViewById(R.id.tv_user_name);
            tvText = (TextView) itemView.findViewById(R.id.tv_status_text);
            tvCreatedAt = (TextView) itemView.findViewById(R.id.tv_created_at);
            tvSource = (TextView) itemView.findViewById(R.id.tv_source);
            tvRepostsCount = (TextView) itemView.findViewById(R.id.tv_reposts_count);
            tvCommentsCount = (TextView) itemView.findViewById(R.id.tv_comments_count);
            tvAttitudesCount = (TextView) itemView.findViewById(R.id.tv_attitudes_count);
            layoutImages = (LinearLayout) itemView.findViewById(R.id.layout_images);
        }
    }
}