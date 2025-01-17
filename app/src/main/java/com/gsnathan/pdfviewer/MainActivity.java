/*
 * MIT License
 *
 * Copyright (c) 2018 Gokul Swaminathan
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.gsnathan.pdfviewer;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument;
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;
import com.github.barteksc.pdfviewer.util.Constants;
import com.github.barteksc.pdfviewer.util.FitPolicy;
import com.gsnathan.pdfviewer.databinding.ActivityMainBinding;
import com.gsnathan.pdfviewer.databinding.PasswordDialogBinding;
import com.jaredrummler.cyanea.app.CyaneaAppCompatActivity;
import com.jaredrummler.cyanea.prefs.CyaneaSettingsActivity;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfPasswordException;

import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.NonConfigurationInstance;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

import static android.content.pm.PackageManager.PERMISSION_DENIED;

@EActivity
public class MainActivity extends CyaneaAppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private PrintManager mgr;
    private SharedPreferences prefManager;

    private boolean isBottomNavigationHidden = false;

    private ActivityMainBinding viewBinding;

    private final ActivityResultLauncher<String[]> documentPickerLauncher = registerForActivityResult(
        new OpenDocument(),
        this::openSelectedDocument
    );

    private final ActivityResultLauncher<String> saveToDownloadPermissionLauncher = registerForActivityResult(
        new RequestPermission(),
        this::saveDownloadedFileAfterPermissionRequest
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());
        
        Constants.THUMBNAIL_RATIO = 1f;
        setBottomBarListeners();

        // Workaround for https://stackoverflow.com/questions/38200282/
        StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(builder.build());

        prefManager = PreferenceManager.getDefaultSharedPreferences(this);

        mgr = (PrintManager) getSystemService(PRINT_SERVICE);
        onFirstInstall();
        onFirstUpdate();

        readUriFromIntent(getIntent());
        if (uri == null) {
            pickFile();
            setTitle("");
        } else {
            displayFromUri(uri);
        }
    }

    private void onFirstInstall() {
        boolean isFirstRun = prefManager.getBoolean("FIRSTINSTALL", true);
        if (isFirstRun) {
            startActivity(new Intent(this, MainIntroActivity.class));
            SharedPreferences.Editor editor = prefManager.edit();
            editor.putBoolean("FIRSTINSTALL", false);
            editor.apply();
        }
    }

    private void onFirstUpdate() {
        boolean isFirstRun = prefManager.getBoolean(Utils.getAppVersion(), true);
        if (isFirstRun) {
            Utils.showLog(this);
            SharedPreferences.Editor editor = prefManager.edit();
            editor.putBoolean(Utils.getAppVersion(), false);
            editor.apply();
        }
    }

    private void readUriFromIntent(Intent intent) {
        Uri intentUri = intent.getData();
        if (intentUri == null) {
            return;
        }

        // Happens when the content provider URI used to open the document expires
        if ("content".equals(intentUri.getScheme()) &&
            checkCallingOrSelfUriPermission(intentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) == PERMISSION_DENIED) {
            Log.w(TAG, "No read permission for URI " + intentUri);
            uri = null;
            return;
        }

        uri = intentUri;
    }

    @NonConfigurationInstance
    Uri uri;

    @NonConfigurationInstance
    Integer pageNumber = 0;

    @NonConfigurationInstance
    String pdfPassword;

    private String pdfFileName = "";

    private byte[] downloadedPdfFileContent;

    private final ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(
            new StartActivityForResult(),
            result -> {
                if (uri != null)
                    displayFromUri(uri);
            }
    );

    void shareFile() {
        startActivity(Utils.emailIntent(pdfFileName, "", getResources().getString(R.string.share), uri));
    }

    private void openSelectedDocument(Uri selectedDocumentUri) {
        if (selectedDocumentUri == null) {
            return;
        }

        if (uri == null || selectedDocumentUri.equals(uri)) {
            uri = selectedDocumentUri;
            displayFromUri(uri);
        } else {
            Intent intent = new Intent(this, getClass());
            intent.setData(selectedDocumentUri);
            startActivity(intent);
        }
    }

    private void pickFile() {
        try {
            documentPickerLauncher.launch(new String[] { "application/pdf" });
        } catch (ActivityNotFoundException e) {
            //alert user that file manager not working
            Toast.makeText(this, R.string.toast_pick_file_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void setBottomBarListeners() {
        viewBinding.bottomNavigation.setOnNavigationItemSelectedListener(item -> {
            switch (item.getItemId()) {
                case R.id.pickFile:
                    pickFile();
                    break;
                case R.id.metaFile:
                    if (uri != null)
                        showPdfMetaDialog();
                    break;
                case R.id.shareFile:
                    if (uri != null)
                        shareFile();
                    break;
                case R.id.printFile:
                    if (uri != null)
                        printDocument();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    void configurePdfViewAndLoad(PDFView.Configurator viewConfigurator) {
        if (!prefManager.getBoolean("pdftheme_pref", false)) {
            viewBinding.pdfView.setBackgroundColor(Color.LTGRAY);
        } else {
            viewBinding.pdfView.setBackgroundColor(0xFF212121);
        }
        viewBinding.pdfView.useBestQuality(prefManager.getBoolean("quality_pref", false));
        viewBinding.pdfView.setMinZoom(0.5f);
        viewBinding.pdfView.setMidZoom(2.0f);
        viewBinding.pdfView.setMaxZoom(5.0f);
        viewConfigurator
                .defaultPage(pageNumber)
                .onPageChange(this::setCurrentPage)
                .enableAnnotationRendering(true)
                .enableAntialiasing(prefManager.getBoolean("alias_pref", true))
                .onTap(this::toggleBottomNavigationVisibility)
                .onPageScroll(this::toggleBottomNavigationAccordingToPosition)
                .scrollHandle(new DefaultScrollHandle(this))
                .spacing(10) // in dp
                .onError(this::handleFileOpeningError)
                .onPageError((page, err) -> Log.e(TAG, "Cannot load page " + page, err))
                .pageFitPolicy(FitPolicy.WIDTH)
                .password(pdfPassword)
                .swipeHorizontal(prefManager.getBoolean("scroll_pref", false))
                .autoSpacing(prefManager.getBoolean("scroll_pref", false))
                .pageSnap(prefManager.getBoolean("snap_pref", false))
                .pageFling(prefManager.getBoolean("fling_pref", false))
                .nightMode(prefManager.getBoolean("pdftheme_pref", false))
                .load();
    }

    private void handleFileOpeningError(Throwable exception) {
        if (exception instanceof PdfPasswordException) {
            if (pdfPassword != null) {
                Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_SHORT).show();
                pdfPassword = null;  // prevent the toast from being shown again if the user rotates the screen
            }
            askForPdfPassword();
        } else {
            Toast.makeText(this, R.string.file_opening_error, Toast.LENGTH_LONG).show();
            Log.e(TAG, "Error when opening file", exception);
        }
    }

    private void toggleBottomNavigationAccordingToPosition(int page, float positionOffset) {
        if (positionOffset == 0) {
            showBottomNavigationView();
        } else if (!isBottomNavigationHidden) {
            hideBottomNavigationView();
        }
    }

    private boolean toggleBottomNavigationVisibility(MotionEvent e) {
        if (isBottomNavigationHidden) {
            showBottomNavigationView();
        } else {
            hideBottomNavigationView();
        }
        return true;
    }

    private void hideBottomNavigationView() {
        isBottomNavigationHidden = true;
        viewBinding.bottomNavigation.animate()
                .translationY(viewBinding.bottomNavigation.getHeight())
                .setDuration(100);
    }

    private void showBottomNavigationView() {
        isBottomNavigationHidden = false;
        viewBinding.bottomNavigation.animate()
                .translationY(0)
                .setDuration(100);
    }

    void displayFromUri(Uri uri) {
        pdfFileName = getFileName(uri);
        setTitle(pdfFileName);
        setTaskDescription(new ActivityManager.TaskDescription(pdfFileName));

        String scheme = uri.getScheme();
        if (scheme != null && scheme.contains("http")) {
            // we will get the pdf asynchronously with the DownloadPDFFile object
            viewBinding.progressBar.setVisibility(View.VISIBLE);
            DownloadPDFFile downloadPDFFile = new DownloadPDFFile(this);
            downloadPDFFile.execute(uri.toString());
        } else {
            configurePdfViewAndLoad(viewBinding.pdfView.fromUri(uri));
        }
    }

    public void hideProgressBar() {
        viewBinding.progressBar.setVisibility(View.GONE);
    }

    void saveToFileAndDisplay(byte[] pdfFileContent) {
        downloadedPdfFileContent = pdfFileContent;
        saveToDownloadFolderIfAllowed(pdfFileContent);
        configurePdfViewAndLoad(viewBinding.pdfView.fromBytes(pdfFileContent));
    }

    private void saveToDownloadFolderIfAllowed(byte[] fileContent) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            trySaveToDownloadFolder(fileContent, false);
        } else {
            saveToDownloadPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    private void trySaveToDownloadFolder(byte[] fileContent, boolean showSuccessMessage) {
        try {
            File downloadDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            Utils.writeBytesToFile(downloadDirectory, pdfFileName, fileContent);
            if (showSuccessMessage) {
                Toast.makeText(this, R.string.saved_to_download, Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error while saving file to download folder", e);
            Toast.makeText(this, R.string.save_to_download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void saveDownloadedFileAfterPermissionRequest(boolean isPermissionGranted) {
        if (isPermissionGranted) {
            trySaveToDownloadFolder(downloadedPdfFileContent, true);
        } else {
            Toast.makeText(this, R.string.save_to_download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    void navToSettings() {
        settingsLauncher.launch(new Intent(this, SettingsActivity.class));
    }

    private void setCurrentPage(int page, int pageCount) {
        pageNumber = page;
        setTitle(String.format("%s %s / %s", pdfFileName + " ", page + 1, pageCount));
    }

    public String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int indexDisplayName = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (indexDisplayName != -1) {
                        result = cursor.getString(indexDisplayName);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result;
    }

    private void printDocument() {
        mgr.print(pdfFileName, new PdfDocumentAdapter(this, uri), null);
    }

    void askForPdfPassword() {
        PasswordDialogBinding dialogBinding = PasswordDialogBinding.inflate(getLayoutInflater());
        AlertDialog alert = new AlertDialog.Builder(this)
                .setTitle(R.string.protected_pdf)
                .setView(dialogBinding.getRoot())
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    pdfPassword = dialogBinding.passwordInput.getText().toString();
                    displayFromUri(uri);
                })
                .setIcon(R.drawable.lock_icon)
                .create();
        alert.setCanceledOnTouchOutside(false);
        alert.show();
    }

    void showPdfMetaDialog() {
        PdfDocument.Meta meta = viewBinding.pdfView.getDocumentMeta();
        if (meta != null) {
            Bundle dialogArgs = new Bundle();
            dialogArgs.putString(PdfMetaDialog.TITLE_ARGUMENT, meta.getTitle());
            dialogArgs.putString(PdfMetaDialog.AUTHOR_ARGUMENT, meta.getAuthor());
            dialogArgs.putString(PdfMetaDialog.CREATION_DATE_ARGUMENT, meta.getCreationDate());
            DialogFragment dialog = new PdfMetaDialog();
            dialog.setArguments(dialogArgs);
            dialog.show(getSupportFragmentManager(), "meta_dialog");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(@NotNull Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_about:
                startActivity(Utils.navIntent(this, AboutActivity.class));
                return true;
            case R.id.theme:
                startActivity(Utils.navIntent(getApplicationContext(), CyaneaSettingsActivity.class));
                return true;
            case R.id.settings:
                navToSettings();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public static class PdfMetaDialog extends DialogFragment {

        public static final String TITLE_ARGUMENT = "title";
        public static final String AUTHOR_ARGUMENT = "author";
        public static final String CREATION_DATE_ARGUMENT = "creation_date";

        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
            return builder.setTitle(R.string.meta)
                    .setMessage(getString(R.string.pdf_title, getArguments().getString(TITLE_ARGUMENT)) + "\n" +
                            getString(R.string.pdf_author, getArguments().getString(AUTHOR_ARGUMENT)) + "\n" +
                            getString(R.string.pdf_creation_date, getArguments().getString(CREATION_DATE_ARGUMENT)))
                    .setPositiveButton(R.string.ok, (dialog, which) -> {})
                    .setIcon(R.drawable.alert_icon)
                    .create();
        }
    }
}

