package com.tyron.code.ui.project;

import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.github.angads25.filepicker.model.DialogConfigs;
import com.github.angads25.filepicker.model.DialogProperties;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.tyron.builder.project.Project;
import com.tyron.code.R;
import com.tyron.code.ui.file.FilePickerDialogFixed;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.completion.progress.ProgressManager;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportConfigCallback;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FS;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.File;
import java.util.concurrent.Executors;

public class CloneProjectFragment extends Fragment {

    public interface OnProjectClonedListener {
        void onProjectCloned(Project project);
    }

    private TextInputLayout mRepoUrlLayout;
    private TextInputEditText mRepoUrlInput;
    private TextInputLayout mUsernameLayout;
    private TextInputEditText mUsernameInput;
    private TextInputLayout mPasswordLayout;
    private TextInputEditText mPasswordInput;
    private TextInputLayout mCertificateLayout;
    private TextInputEditText mCertificateInput;
    private MaterialButton mCertificateButton;
    private View mLoadingView;
    private MaterialButton mCloneButton;

    private OnProjectClonedListener mListener;
    public void setOnProjectClonedListener(OnProjectClonedListener listener) {
        mListener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.clone_project_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> getParentFragmentManager().popBackStack());

        mRepoUrlLayout = view.findViewById(R.id.repo_url_layout);
        mRepoUrlInput = view.findViewById(R.id.repo_url_input);
        mUsernameLayout = view.findViewById(R.id.auth_username_layout);
        mUsernameInput = view.findViewById(R.id.auth_username_input);
        mPasswordLayout = view.findViewById(R.id.auth_password_layout);
        mPasswordInput = view.findViewById(R.id.auth_password_input);
        mCertificateLayout = view.findViewById(R.id.auth_certificate_layout);
        mCertificateInput = view.findViewById(R.id.auth_certificate_input);
        mCertificateButton = view.findViewById(R.id.auth_certificate_button);
        mLoadingView = view.findViewById(R.id.clone_loading);
        mCloneButton = view.findViewById(R.id.clone_button);

        MaterialRadioButton authNone = view.findViewById(R.id.auth_none);
        MaterialRadioButton authPassword = view.findViewById(R.id.auth_password);
        MaterialRadioButton authCertificate = view.findViewById(R.id.auth_certificate);

        toggleAuthFields(AuthMode.NONE);
        toggleLoading(false);

        authNone.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                toggleAuthFields(AuthMode.NONE);
            }
        });
        authPassword.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                toggleAuthFields(AuthMode.PASSWORD);
            }
        });
        authCertificate.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                toggleAuthFields(AuthMode.CERTIFICATE);
            }
        });

        mCertificateButton.setOnClickListener(v -> showCertificatePicker());

        view.findViewById(R.id.cancel_button).setOnClickListener(v ->
                getParentFragmentManager().popBackStack());
        mCloneButton.setOnClickListener(v -> startClone(getSelectedAuthMode()));
    }

    private void toggleAuthFields(AuthMode mode) {
        boolean showPassword = mode == AuthMode.PASSWORD;
        boolean showCertificate = mode == AuthMode.CERTIFICATE;
        mUsernameLayout.setVisibility(showPassword ? View.VISIBLE : View.GONE);
        mPasswordLayout.setVisibility(showPassword ? View.VISIBLE : View.GONE);
        mCertificateLayout.setVisibility(showCertificate ? View.VISIBLE : View.GONE);
        mCertificateButton.setVisibility(showCertificate ? View.VISIBLE : View.GONE);
    }

    private AuthMode getSelectedAuthMode() {
        View view = getView();
        if (view == null) {
            return AuthMode.NONE;
        }
        if (((MaterialRadioButton) view.findViewById(R.id.auth_password)).isChecked()) {
            return AuthMode.PASSWORD;
        }
        if (((MaterialRadioButton) view.findViewById(R.id.auth_certificate)).isChecked()) {
            return AuthMode.CERTIFICATE;
        }
        return AuthMode.NONE;
    }

    private void showCertificatePicker() {
        DialogProperties properties = new DialogProperties();
        properties.selection_type = DialogConfigs.FILE_SELECT;
        properties.selection_mode = DialogConfigs.SINGLE_MODE;
        properties.root = new File("/");
        FilePickerDialogFixed dialog = new FilePickerDialogFixed(requireContext(), properties);
        dialog.setTitle(R.string.clone_project_choose_certificate);
        dialog.setDialogSelectionListener(files -> {
            if (files.length > 0) {
                mCertificateInput.setText(files[0]);
            }
        });
        dialog.show();
    }

    private void startClone(AuthMode mode) {
        String url = mRepoUrlInput.getText() == null ? "" : mRepoUrlInput.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            mRepoUrlLayout.setError(getString(R.string.clone_project_repo_url_error));
            return;
        }
        mRepoUrlLayout.setError(null);

        String repoName = parseRepositoryName(url);
        if (TextUtils.isEmpty(repoName)) {
            AndroidUtilities.showSimpleAlert(requireContext(), getString(R.string.error),
                    getString(R.string.clone_project_repo_url_error));
            return;
        }

        File targetDir = new File(getProjectSavePath(), repoName);
        if (targetDir.exists()) {
            AndroidUtilities.showSimpleAlert(requireContext(), getString(R.string.error),
                    getString(R.string.clone_project_target_exists));
            return;
        }

        CredentialsProvider credentialsProvider = null;
        String username = null;
        String password = null;
        if (mode == AuthMode.PASSWORD) {
            username = mUsernameInput.getText() == null ? "" :
                    mUsernameInput.getText().toString().trim();
            password = mPasswordInput.getText() == null ? "" :
                    mPasswordInput.getText().toString();
            if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
                AndroidUtilities.showSimpleAlert(requireContext(), getString(R.string.error),
                        getString(R.string.clone_project_auth_missing));
                return;
            }
            credentialsProvider = new UsernamePasswordCredentialsProvider(username, password);
        } else if (mode == AuthMode.CERTIFICATE) {
            String certPath = mCertificateInput.getText() == null ? "" :
                    mCertificateInput.getText().toString().trim();
            if (TextUtils.isEmpty(certPath)) {
                AndroidUtilities.showSimpleAlert(requireContext(), getString(R.string.error),
                        getString(R.string.clone_project_certificate_missing));
                return;
            }
            if (!new File(certPath).exists()) {
                AndroidUtilities.showSimpleAlert(requireContext(), getString(R.string.error),
                        getString(R.string.clone_project_certificate_not_found));
                return;
            }
        }

        toggleLoading(true);
        mCloneButton.setEnabled(false);

        CredentialsProvider finalCredentialsProvider = credentialsProvider;
        AuthMode finalMode = mode;
        String certPath = mCertificateInput.getText() == null ? "" :
                mCertificateInput.getText().toString().trim();
        Executors.newSingleThreadExecutor().execute(() -> {
            String errorMessage = null;
            Project project = null;
            try {
                CloneCommand cloneCommand = Git.cloneRepository()
                        .setURI(url)
                        .setDirectory(targetDir);
                if (finalCredentialsProvider != null) {
                    cloneCommand.setCredentialsProvider(finalCredentialsProvider);
                }
                if (finalMode == AuthMode.CERTIFICATE) {
                    cloneCommand.setTransportConfigCallback(createSshCallback(certPath));
                }
                try (Git git = cloneCommand.call()) {
                    project = new Project(git.getRepository().getDirectory().getParentFile());
                }
            } catch (Exception e) {
                errorMessage = e.getMessage();
            }

            Project clonedProject = project;
            String message = errorMessage;
            if (getActivity() != null) {
                requireActivity().runOnUiThread(() -> {
                    toggleLoading(false);
                    mCloneButton.setEnabled(true);
                    if (message != null) {
                        AndroidUtilities.showSimpleAlert(requireContext(), getString(R.string.error),
                                message);
                    } else if (clonedProject != null) {
                        if (mListener != null) {
                            mListener.onProjectCloned(clonedProject);
                        }
                    }
                });
            }
        });
    }

    private TransportConfigCallback createSshCallback(String certPath) {
        SshSessionFactory sshSessionFactory = new JschConfigSessionFactory() {
            @Override
            protected void configure(OpenSshConfig.Host host, Session session) {
                session.setConfig("StrictHostKeyChecking", "no");
            }

            @Override
            protected JSch createDefaultJSch(FS fs) throws JSchException {
                JSch jsch = super.createDefaultJSch(fs);
                jsch.removeAllIdentity();
                jsch.addIdentity(certPath);
                return jsch;
            }
        };
        return new TransportConfigCallback() {
            @Override
            public void configure(Transport transport) {
                if (transport instanceof SshTransport) {
                    ((SshTransport) transport).setSshSessionFactory(sshSessionFactory);
                }
            }
        };
    }

    private String parseRepositoryName(String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - 4);
        }
        int slash = trimmed.lastIndexOf('/');
        if (slash >= 0 && slash < trimmed.length() - 1) {
            return trimmed.substring(slash + 1);
        }
        return "";
    }

    private String getProjectSavePath() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return requireContext().getExternalFilesDir("Projects").getAbsolutePath();
        }
        return PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString(SharedPreferenceKeys.PROJECT_SAVE_PATH,
                        requireContext().getExternalFilesDir("Projects").getAbsolutePath());
    }

    private void toggleLoading(boolean show) {
        ProgressManager.getInstance().runLater(() -> {
            if (getActivity() == null || isDetached()) {
                return;
            }
            mLoadingView.setVisibility(show ? View.VISIBLE : View.GONE);
        }, 0);
    }

    private enum AuthMode {
        NONE,
        PASSWORD,
        CERTIFICATE
    }
}
