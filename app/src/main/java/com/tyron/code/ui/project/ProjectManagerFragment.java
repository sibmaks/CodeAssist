package com.tyron.code.ui.project;

import android.Manifest;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewKt;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.TransitionManager;

import com.github.angads25.filepicker.model.DialogConfigs;
import com.github.angads25.filepicker.model.DialogProperties;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.transition.MaterialFade;
import com.google.android.material.transition.MaterialFadeThrough;
import com.google.android.material.transition.MaterialSharedAxis;
import com.tyron.builder.project.Project;
import com.tyron.code.R;
import com.tyron.code.ui.file.FilePickerDialogFixed;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.project.adapter.ProjectManagerAdapter;
import com.tyron.code.ui.settings.SettingsActivity;
import com.tyron.code.ui.wizard.WizardFragment;
import com.tyron.code.util.UiUtilsKt;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.completion.progress.ProgressManager;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;

public class ProjectManagerFragment extends Fragment {

    public static final String TAG = ProjectManagerFragment.class.getSimpleName();

    private SharedPreferences mPreferences;
    private RecyclerView mRecyclerView;
    private ProjectManagerAdapter mAdapter;
    private ExtendedFloatingActionButton mCreateProjectFab;
    private boolean mShowDialogOnPermissionGrant;
    private ActivityResultLauncher<String[]> mPermissionLauncher;
    private final ActivityResultContracts.RequestMultiplePermissions mPermissionsContract =
            new ActivityResultContracts.RequestMultiplePermissions();

    private String mPreviousPath;

    private FilePickerDialogFixed mDirectoryPickerDialog;
    private Runnable mAfterSavePathSelection;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));
        mPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        mPermissionLauncher = registerForActivityResult(mPermissionsContract, isGranted -> {
            if (isGranted.containsValue(false)) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.project_manager_permission_denied)
                        .setMessage(R.string.project_manager_android11_notice)
                        .setPositiveButton(R.string.project_manager_button_request_again, (d, which) -> {
                            mShowDialogOnPermissionGrant = true;
                            requestPermissions();
                        })
                        .setNegativeButton(R.string.project_manager_button_continue, (d, which) -> {
                            mShowDialogOnPermissionGrant = false;
                            setSavePath(Environment.getExternalStorageDirectory().getAbsolutePath());
                        })
                        .show();
                setSavePath(Environment.getExternalStorageDirectory().getAbsolutePath());
            } else {
                if (mShowDialogOnPermissionGrant) {
                    mShowDialogOnPermissionGrant = false;
                    showDirectorySelectDialog(mAfterSavePathSelection);
                }
            }
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);


        toolbar.inflateMenu(R.menu.project_list_fragment_menu);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // can't change project path on android R
            toolbar.getMenu().removeItem(R.id.projects_path);
        }
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();

            if (id == R.id.projects_path) {
                checkSavePath();
                return true;
            }

            if (id == R.id.menu_settings) {
                Intent intent = new Intent();
                intent.setClass(requireActivity(), SettingsActivity.class);
                startActivity(intent);
                return true;
            }

            return true;
        });


        mCreateProjectFab = view.findViewById(R.id.create_project_fab);
        mCreateProjectFab.setOnClickListener(v -> showCreateProjectDialog());
        UiUtilsKt.addSystemWindowInsetToMargin(mCreateProjectFab, false, false, false, true);

        mAdapter = new ProjectManagerAdapter();
        mAdapter.setOnProjectSelectedListener(this::openProject);
        mAdapter.setOnProjectLongClickListener(this::inflateProjectMenus);
        mRecyclerView = view.findViewById(R.id.projects_recycler);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mRecyclerView.setAdapter(mAdapter);
    }

    private boolean inflateProjectMenus(View view, Project project) {
        view.setOnCreateContextMenuListener((menu, v, menuInfo) -> {
            menu.add(R.string.dialog_delete)
                    .setOnMenuItemClickListener(item -> {
                        String message = getString(R.string.dialog_confirm_delete,
                                project.getRootFile().getName());
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.dialog_delete)
                                .setMessage(message)
                                .setPositiveButton(android.R.string.yes,
                                        (d, which) -> deleteProject(project))
                                .setNegativeButton(android.R.string.no, null)
                                .show();
                        return true;
                    });
        });
        view.showContextMenu();
        return true;
    }

    private void deleteProject(Project project) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                FileUtils.forceDelete(project.getRootFile());
                if (getActivity() != null) {
                    requireActivity().runOnUiThread(() -> {
                        AndroidUtilities.showSimpleAlert(
                                requireContext(),
                                getString(R.string.success),
                                getString(R.string.delete_success));
                        loadProjects();
                    });
                }
            } catch (IOException e) {
                if (getActivity() != null) {
                    requireActivity().runOnUiThread(() ->
                            AndroidUtilities.showSimpleAlert(requireContext(),
                                    getString(R.string.error),
                                    e.getMessage()));
                }
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.project_manager_fragment, container, false);
    }

    @Override
    public void onResume() {
        super.onResume();

        checkSavePath();
    }

    private void checkSavePath() {
        String path = mPreferences.getString(SharedPreferenceKeys.PROJECT_SAVE_PATH, null);
        if (path == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (permissionsGranted()) {
                showDirectorySelectDialog(null);
            } else if (shouldShowRequestPermissionRationale()) {
                if (shouldShowRequestPermissionRationale()) {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setMessage(R.string.project_manager_permission_rationale)
                            .setPositiveButton(R.string.project_manager_button_allow, (d, which) -> {
                                mShowDialogOnPermissionGrant = true;
                                requestPermissions();
                            })
                            .setNegativeButton(R.string.project_manager_button_use_internal, (d, which) ->
                                    setSavePath(Environment.getExternalStorageDirectory().getAbsolutePath()))
                            .setTitle(R.string.project_manager_rationale_title)
                            .show();
                }
            } else {
                requestPermissions();
            }
        } else {
            loadProjects();
        }
    }

    private void setSavePath(String path) {
        mPreferences.edit()
                .putString(SharedPreferenceKeys.PROJECT_SAVE_PATH, path)
                .apply();
        loadProjects();
    }

    @VisibleForTesting
    String getPreviousPath() {
        return mPreviousPath;
    }

    @VisibleForTesting
    FilePickerDialogFixed getDirectoryPickerDialog() {
        return mDirectoryPickerDialog;
    }

    private void showDirectorySelectDialog(@Nullable Runnable afterSelection) {
        mAfterSavePathSelection = afterSelection;
        DialogProperties properties = new DialogProperties();
        properties.selection_type = DialogConfigs.DIR_SELECT;
        properties.selection_mode = DialogConfigs.SINGLE_MODE;
        properties.root = Environment.getExternalStorageDirectory();
        if (mPreviousPath != null && new File(mPreviousPath).exists()) {
            properties.offset = new File(mPreviousPath);
        }
        FilePickerDialogFixed dialogFixed = new FilePickerDialogFixed(requireContext(), properties);
        dialogFixed.setTitle(R.string.project_manager_save_location_title);
        dialogFixed.setDialogSelectionListener(files -> {
            setSavePath(files[0]);
            if (mAfterSavePathSelection != null) {
                mAfterSavePathSelection.run();
                mAfterSavePathSelection = null;
            }
        });
        dialogFixed.setOnDismissListener(__ -> {
            mPreviousPath = dialogFixed.getCurrentPath();
            mDirectoryPickerDialog = null;
        });
        dialogFixed.show();

        mDirectoryPickerDialog = dialogFixed;
    }

    private void showCreateProjectDialog() {
        CharSequence[] options = new CharSequence[]{
                getString(R.string.project_manager_add_existing_project),
                getString(R.string.project_manager_create_from_template)
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.project_manager_add_project_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showOpenExistingProjectDialog();
                    } else {
                        showTemplateLocationDialog();
                    }
                })
                .show();
    }

    private void showOpenExistingProjectDialog() {
        DialogProperties properties = new DialogProperties();
        properties.selection_type = DialogConfigs.DIR_SELECT;
        properties.selection_mode = DialogConfigs.SINGLE_MODE;
        properties.root = Environment.getExternalStorageDirectory();
        if (mPreviousPath != null && new File(mPreviousPath).exists()) {
            properties.offset = new File(mPreviousPath);
        }
        FilePickerDialogFixed dialogFixed = new FilePickerDialogFixed(requireContext(), properties);
        dialogFixed.setTitle(R.string.project_manager_open_existing_title);
        dialogFixed.setDialogSelectionListener(files -> {
            Project project = new Project(new File(files[0]));
            openProject(project);
        });
        dialogFixed.setOnDismissListener(__ -> {
            mPreviousPath = dialogFixed.getCurrentPath();
        });
        dialogFixed.show();
    }

    private void showTemplateLocationDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.project_manager_save_new_project_title)
                    .setMessage(R.string.project_manager_android11_notice)
                    .setPositiveButton(R.string.project_manager_button_continue,
                            (dialog, which) -> startTemplateWizard(true))
                    .show();
            return;
        }

        CharSequence[] options = new CharSequence[]{
                getString(R.string.project_manager_save_internal),
                getString(R.string.project_manager_save_external)
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.project_manager_save_new_project_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        startTemplateWizard(true);
                    } else {
                        requestExternalTemplateLocation();
                    }
                })
                .show();
    }

    private void requestExternalTemplateLocation() {
        if (permissionsGranted()) {
            showDirectorySelectDialog(() -> startTemplateWizard(false));
            return;
        }

        if (shouldShowRequestPermissionRationale()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.project_manager_permission_rationale)
                    .setPositiveButton(R.string.project_manager_button_allow, (d, which) -> {
                        mShowDialogOnPermissionGrant = true;
                        mAfterSavePathSelection = () -> startTemplateWizard(false);
                        requestPermissions();
                    })
                    .setNegativeButton(R.string.project_manager_button_use_internal,
                            (d, which) -> startTemplateWizard(true))
                    .setTitle(R.string.project_manager_rationale_title)
                    .show();
            return;
        }

        mShowDialogOnPermissionGrant = true;
        mAfterSavePathSelection = () -> startTemplateWizard(false);
        requestPermissions();
    }

    private void startTemplateWizard(boolean useInternalStorage) {
        if (useInternalStorage) {
            setSavePath(requireContext().getExternalFilesDir("Projects").getAbsolutePath());
        }
        WizardFragment wizardFragment = new WizardFragment();
        wizardFragment.setUseInternalStorage(useInternalStorage);
        wizardFragment.setOnProjectCreatedListener(this::openProject);
        getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, wizardFragment)
                .addToBackStack(null)
                .commit();
    }

    private boolean permissionsGranted() {
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean shouldShowRequestPermissionRationale() {
        return shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE) ||
                shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void requestPermissions() {
        mPermissionLauncher.launch(
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE});
    }

    private void openProject(Project project) {
        MainFragment fragment = MainFragment.newInstance(project.getRootFile().getAbsolutePath());
        getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit();
    }

    private void loadProjects() {
        toggleLoading(true);

        Executors.newSingleThreadExecutor().execute(() -> {
            String path;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                path = requireContext().getExternalFilesDir("Projects").getAbsolutePath();
            } else {
                path = mPreferences.getString(SharedPreferenceKeys.PROJECT_SAVE_PATH,
                        requireContext().getExternalFilesDir("Projects").getAbsolutePath());
            }
            File projectDir = new File(path);
            File[] directories = projectDir.listFiles(File::isDirectory);

            List<Project> projects = new ArrayList<>();
            if (directories != null) {
                Arrays.sort(directories, Comparator.comparingLong(File::lastModified));
                for (File directory : directories) {
                    File appModule = new File(directory, "app");
                    if (appModule.exists()) {
                        Project project = new Project(new File(directory.getAbsolutePath()
                                .replaceAll("%20", " ")));
                        // if (project.isValidProject()) {
                        projects.add(project);
                        // }
                    }
                }
            }

            if (getActivity() != null) {
                requireActivity().runOnUiThread(() -> {
                    toggleLoading(false);
                    ProgressManager.getInstance().runLater(() -> {
                        mAdapter.submitList(projects);
                        toggleNullProject(projects);
                    }, 300);
                });
            }
        });
    }

    private void toggleNullProject(List<Project> projects) {
        ProgressManager.getInstance().runLater(() -> {
            if (getActivity() == null || isDetached()) {
                return;
            }
            View view = getView();
            if (view == null) {
                return;
            }

            View recycler = view.findViewById(R.id.projects_recycler);
            View empty = view.findViewById(R.id.empty_projects);

            TransitionManager.beginDelayedTransition(
                    (ViewGroup) recycler.getParent(), new MaterialFade());
            if (projects.size() == 0) {
                recycler.setVisibility(View.GONE);
                empty.setVisibility(View.VISIBLE);
            } else {
                recycler.setVisibility(View.VISIBLE);
                empty.setVisibility(View.GONE);
            }
        }, 300);
    }

    private void toggleLoading(boolean show) {
        ProgressManager.getInstance().runLater(() -> {
            if (getActivity() == null || isDetached()) {
                return;
            }
            View view = getView();
            if (view == null) {
                return;
            }
            View recycler = view.findViewById(R.id.projects_recycler);
            View empty = view.findViewById(R.id.empty_container);
            View empty_project = view.findViewById(R.id.empty_projects);
            empty_project.setVisibility(View.GONE);

            TransitionManager.beginDelayedTransition((ViewGroup) recycler.getParent(),
                                                     new MaterialFade());
            if (show) {
                recycler.setVisibility(View.GONE);
                empty.setVisibility(View.VISIBLE);
            } else {
                recycler.setVisibility(View.VISIBLE);
                empty.setVisibility(View.GONE);
            }
        }, 300);
    }
}
