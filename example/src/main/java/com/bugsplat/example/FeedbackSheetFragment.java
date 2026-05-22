package com.bugsplat.example;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bugsplat.android.BugSplat;
import com.bugsplat.android.FeedbackResult;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The User Feedback bottom sheet. Holds two states in a single layout
 * ({@code sheet_feedback.xml}): the input form and the thank-you confirmation,
 * swapped in place once feedback is submitted.
 */
public class FeedbackSheetFragment extends BottomSheetDialogFragment {

    /** Lets the host activity log the event and refresh its recent-activity list. */
    interface FeedbackListener {
        void onFeedbackSent(String title);
    }

    private FeedbackListener listener;
    private ActivityResultLauncher<String> pickFileLauncher;
    private FeedbackAttachments.Picked pickedAttachment;
    private FeedbackResult result;

    private View formGroup;
    private View thanksGroup;
    private MaterialButtonToggleGroup categoryGroup;
    private TextInputLayout titleLayout;
    private TextInputEditText titleInput;
    private TextInputEditText descriptionInput;
    private TextInputEditText nameInput;
    private TextInputEditText emailInput;
    private CheckBox includeLogsCheckbox;
    private MaterialButton submitButton;
    private View attachmentRow;
    private TextView attachmentName;
    private TextView attachmentMeta;
    private MaterialButton attachmentAction;
    private TextView reportIdView;

    static FeedbackSheetFragment newInstance() {
        return new FeedbackSheetFragment();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof FeedbackListener) {
            listener = (FeedbackListener) context;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Must be registered before the fragment reaches STARTED.
        pickFileLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        onFilePicked(uri);
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_feedback, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        formGroup = view.findViewById(R.id.feedbackFormGroup);
        thanksGroup = view.findViewById(R.id.feedbackThanksGroup);
        categoryGroup = view.findViewById(R.id.categoryToggleGroup);
        titleLayout = view.findViewById(R.id.feedbackTitleLayout);
        titleInput = view.findViewById(R.id.feedbackTitle);
        descriptionInput = view.findViewById(R.id.feedbackDescription);
        nameInput = view.findViewById(R.id.feedbackName);
        emailInput = view.findViewById(R.id.feedbackEmail);
        includeLogsCheckbox = view.findViewById(R.id.feedbackIncludeLogs);
        submitButton = view.findViewById(R.id.feedbackSubmit);
        attachmentRow = view.findViewById(R.id.attachmentRow);
        attachmentName = view.findViewById(R.id.attachmentName);
        attachmentMeta = view.findViewById(R.id.attachmentMeta);
        attachmentAction = view.findViewById(R.id.attachmentAction);
        reportIdView = view.findViewById(R.id.thanksReportId);

        view.findViewById(R.id.feedbackClose).setOnClickListener(v -> dismiss());
        attachmentRow.setOnClickListener(v -> launchPicker());
        attachmentAction.setOnClickListener(v -> launchPicker());
        submitButton.setOnClickListener(v -> submitFeedback());

        view.findViewById(R.id.thanksCopy).setOnClickListener(v -> copyReportId());
        view.findViewById(R.id.thanksViewDashboard).setOnClickListener(v -> openDashboard());
        view.findViewById(R.id.thanksClose).setOnClickListener(v -> dismiss());

        // Make the "BugSplat" anchor in "Powered by BugSplat" tappable.
        ((TextView) view.findViewById(R.id.feedbackPoweredBy))
                .setMovementMethod(LinkMovementMethod.getInstance());

        // Clear the required-Title error as soon as the user starts typing.
        titleInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (titleLayout.getError() != null) {
                    titleLayout.setError(null);
                }
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });

        renderAttachment();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog) {
            View sheet = ((BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                // Let our rounded bg_sheet drawable define the corners.
                sheet.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }
    }

    // ---- Attachment ----

    private void launchPicker() {
        pickFileLauncher.launch("*/*");
    }

    private void onFilePicked(Uri uri) {
        attachmentName.setText(R.string.feedback_loading);
        attachmentMeta.setVisibility(View.GONE);
        Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            FeedbackAttachments.Picked picked = FeedbackAttachments.copyUriToCache(appContext, uri);
            runOnUi(() -> {
                if (picked != null) {
                    pickedAttachment = picked;
                }
                renderAttachment();
            });
        }).start();
    }

    private void renderAttachment() {
        if (pickedAttachment != null) {
            attachmentName.setText(pickedAttachment.displayName);
            attachmentMeta.setText(pickedAttachment.metaLine(requireContext()));
            attachmentMeta.setVisibility(View.VISIBLE);
            attachmentAction.setVisibility(View.VISIBLE);
        } else {
            attachmentName.setText(R.string.feedback_add_attachment);
            attachmentMeta.setVisibility(View.GONE);
            attachmentAction.setVisibility(View.GONE);
        }
    }

    // ---- Submit ----

    private void submitFeedback() {
        final String title = textOf(titleInput);
        if (title.isEmpty()) {
            titleLayout.setError(getString(R.string.feedback_title_required));
            return;
        }
        titleLayout.setError(null);

        final String description = textOf(descriptionInput);
        final String name = textOf(nameInput);
        final String email = textOf(emailInput);
        final String category = selectedCategory();
        final boolean includeLogs = includeLogsCheckbox.isChecked();
        final File attachmentFile = pickedAttachment != null ? pickedAttachment.file : null;
        final Context appContext = requireContext().getApplicationContext();

        setFormEnabled(false);
        submitButton.setText(R.string.feedback_submitting);

        new Thread(() -> {
            List<File> attachments = new ArrayList<>();
            if (includeLogs) {
                File logFile = FeedbackAttachments.createSampleLogFile(appContext);
                if (logFile != null) {
                    attachments.add(logFile);
                }
            }
            if (attachmentFile != null) {
                attachments.add(attachmentFile);
            }

            Map<String, String> attributes = new HashMap<>();
            attributes.put("category", category);

            FeedbackResult r = BugSplat.postFeedbackBlockingWithResult(
                    BuildConfig.BUGSPLAT_DATABASE,
                    BuildConfig.BUGSPLAT_APP_NAME,
                    BuildConfig.BUGSPLAT_APP_VERSION,
                    title,
                    description,
                    name.isEmpty() ? null : name,
                    email.isEmpty() ? null : email,
                    null,
                    attachments.isEmpty() ? null : attachments,
                    attributes);

            runOnUi(() -> onSubmitFinished(r, title));
        }).start();
    }

    private void onSubmitFinished(FeedbackResult r, String title) {
        if (r.isSuccess()) {
            result = r;
            showThanks(r);
            if (listener != null) {
                listener.onFeedbackSent(title);
            }
        } else {
            setFormEnabled(true);
            submitButton.setText(R.string.feedback_submit);
            Toast.makeText(requireContext(), R.string.feedback_error, Toast.LENGTH_LONG).show();
        }
    }

    private void showThanks(FeedbackResult r) {
        formGroup.setVisibility(View.GONE);
        thanksGroup.setVisibility(View.VISIBLE);
        reportIdView.setText(r.getCrashId() != null
                ? String.valueOf(r.getCrashId())
                : getString(R.string.feedback_report_id_unavailable));
    }

    // ---- Thank-you actions ----

    private void copyReportId() {
        if (result == null || result.getCrashId() == null) {
            return;
        }
        ClipboardManager clipboard =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    "BugSplat Report ID", String.valueOf(result.getCrashId())));
            Toast.makeText(requireContext(), R.string.feedback_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void openDashboard() {
        Uri uri = DashboardUrls.forReport(
                BuildConfig.BUGSPLAT_DATABASE,
                result != null ? result.getCrashId() : null);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.feedback_no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Helpers ----

    private String selectedCategory() {
        int checkedId = categoryGroup.getCheckedButtonId();
        if (checkedId == R.id.catFeature) {
            return "Feature";
        }
        if (checkedId == R.id.catOther) {
            return "Other";
        }
        return "Bug";
    }

    private void setFormEnabled(boolean enabled) {
        submitButton.setEnabled(enabled);
        titleInput.setEnabled(enabled);
        descriptionInput.setEnabled(enabled);
        nameInput.setEnabled(enabled);
        emailInput.setEnabled(enabled);
        includeLogsCheckbox.setEnabled(enabled);
        attachmentRow.setEnabled(enabled);
        attachmentRow.setClickable(enabled);
        attachmentAction.setEnabled(enabled);
        for (int i = 0; i < categoryGroup.getChildCount(); i++) {
            categoryGroup.getChildAt(i).setEnabled(enabled);
        }
    }

    private static String textOf(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private void runOnUi(Runnable action) {
        if (isAdded() && getActivity() != null) {
            requireActivity().runOnUiThread(action);
        }
    }
}
