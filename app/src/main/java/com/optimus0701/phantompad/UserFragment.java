package com.optimus0701.phantompad;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

public class UserFragment extends Fragment {

    // ViewFlipper states
    private static final int STATE_AUTH_FORM = 0;
    private static final int STATE_OTP = 1;
    private static final int STATE_FORGOT_PASSWORD = 2;
    private static final int STATE_RESET_PASSWORD = 3;
    private static final int STATE_LOGGED_IN = 4;

    // Form flipper states
    private static final int FORM_SIGN_IN = 0;
    private static final int FORM_SIGN_UP = 1;

    private SharedPreferences mPrefs;

    // Main ViewFlipper
    private ViewFlipper viewFlipper;
    private ViewFlipper formFlipper;

    // Tabs
    private TextView tabSignIn;
    private TextView tabSignUp;

    // Sign In form
    private TextInputLayout tilLoginEmail, tilLoginPassword;
    private TextInputEditText etLoginEmail, etLoginPassword;
    private CheckBox cbRememberMe;
    private TextView tvForgotPassword;
    private TextView tvLoginError;
    private Button btnSignIn;
    private ProgressBar progressLogin;

    // Sign Up form
    private TextInputLayout tilSignupEmail, tilSignupPassword, tilSignupConfirmPassword, tilSignupName;
    private TextInputEditText etSignupEmail, etSignupPassword, etSignupConfirmPassword, etSignupName;
    private TextView tvSignupError;
    private Button btnSignUp;
    private ProgressBar progressSignup;

    // OTP
    private TextInputEditText etOtp;
    private TextView tvOtpMessage, tvOtpError;
    private Button btnVerifyOtp;
    private ProgressBar progressOtp;
    private TextView btnOtpBack;

    // Forgot password
    private TextInputEditText etForgotEmail;
    private Button btnSendOtp;
    private ProgressBar progressForgot;
    private TextView btnForgotBack;

    // Reset password
    private TextInputEditText etResetOtp, etResetNewPassword;
    private Button btnResetPassword;
    private ProgressBar progressReset;
    private TextView btnResetBack;

    // Logged in
    private TextView tvUserName, tvUserEmail;
    private TextView tvPremiumStatus, tvPremiumExpiry;
    private MaterialCardView cardPremium;
    private Button btnBuyPremium;
    private Button btnLogout;
    private Button btnSwitchLanguage;
    private ProgressBar progressBar;

    // Language (standalone on auth form)
    private TextView btnSwitchLanguageStandalone;

    // State tracking
    private String otpEmail = "";
    private String forgotEmail = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_user, container, false);

        if (getContext() == null) return view;
        mPrefs = getContext().getSharedPreferences("phantom_mic_module", Context.MODE_PRIVATE);

        bindViews(view);
        setupTabToggle();
        setupSignInForm();
        setupSignUpForm();
        setupOtpForm();
        setupForgotPasswordForm();
        setupResetPasswordForm();
        setupLoggedInState();
        setupLanguageSwitch();

        // Check auth state
        updateUI();

        return view;
    }

    private void bindViews(View view) {
        viewFlipper = view.findViewById(R.id.view_flipper);
        formFlipper = view.findViewById(R.id.form_flipper);

        // Tabs
        tabSignIn = view.findViewById(R.id.tab_sign_in);
        tabSignUp = view.findViewById(R.id.tab_sign_up);

        // Sign In
        tilLoginEmail = view.findViewById(R.id.til_login_email);
        tilLoginPassword = view.findViewById(R.id.til_login_password);
        etLoginEmail = view.findViewById(R.id.et_login_email);
        etLoginPassword = view.findViewById(R.id.et_login_password);
        cbRememberMe = view.findViewById(R.id.cb_remember_me);
        tvForgotPassword = view.findViewById(R.id.tv_forgot_password);
        tvLoginError = view.findViewById(R.id.tv_login_error);
        btnSignIn = view.findViewById(R.id.btn_sign_in);
        progressLogin = view.findViewById(R.id.progress_login);

        // Sign Up
        tilSignupEmail = view.findViewById(R.id.til_signup_email);
        tilSignupPassword = view.findViewById(R.id.til_signup_password);
        tilSignupConfirmPassword = view.findViewById(R.id.til_signup_confirm_password);
        tilSignupName = view.findViewById(R.id.til_signup_name);
        etSignupEmail = view.findViewById(R.id.et_signup_email);
        etSignupPassword = view.findViewById(R.id.et_signup_password);
        etSignupConfirmPassword = view.findViewById(R.id.et_signup_confirm_password);
        etSignupName = view.findViewById(R.id.et_signup_name);
        tvSignupError = view.findViewById(R.id.tv_signup_error);
        btnSignUp = view.findViewById(R.id.btn_sign_up);
        progressSignup = view.findViewById(R.id.progress_signup);

        // OTP
        etOtp = view.findViewById(R.id.et_otp);
        tvOtpMessage = view.findViewById(R.id.tv_otp_message);
        tvOtpError = view.findViewById(R.id.tv_otp_error);
        btnVerifyOtp = view.findViewById(R.id.btn_verify_otp);
        progressOtp = view.findViewById(R.id.progress_otp);
        btnOtpBack = view.findViewById(R.id.btn_otp_back);

        // Forgot password
        etForgotEmail = view.findViewById(R.id.et_forgot_email);
        btnSendOtp = view.findViewById(R.id.btn_send_otp);
        progressForgot = view.findViewById(R.id.progress_forgot);
        btnForgotBack = view.findViewById(R.id.btn_forgot_back);

        // Reset password
        etResetOtp = view.findViewById(R.id.et_reset_otp);
        etResetNewPassword = view.findViewById(R.id.et_reset_new_password);
        btnResetPassword = view.findViewById(R.id.btn_reset_password);
        progressReset = view.findViewById(R.id.progress_reset);
        btnResetBack = view.findViewById(R.id.btn_reset_back);

        // Logged in
        tvUserName = view.findViewById(R.id.tv_user_name);
        tvUserEmail = view.findViewById(R.id.tv_user_email);
        tvPremiumStatus = view.findViewById(R.id.tv_premium_status);
        tvPremiumExpiry = view.findViewById(R.id.tv_premium_expiry);
        cardPremium = view.findViewById(R.id.card_premium);
        btnBuyPremium = view.findViewById(R.id.btn_buy_premium);
        btnLogout = view.findViewById(R.id.btn_logout);
        btnSwitchLanguage = view.findViewById(R.id.btn_switch_language);
        progressBar = view.findViewById(R.id.progress_bar);
        btnSwitchLanguageStandalone = view.findViewById(R.id.btn_switch_language_standalone);
    }

    // ==================== TAB TOGGLE ====================

    private void setupTabToggle() {
        tabSignIn.setOnClickListener(v -> switchToTab(FORM_SIGN_IN));
        tabSignUp.setOnClickListener(v -> switchToTab(FORM_SIGN_UP));
    }

    private void switchToTab(int tab) {
        formFlipper.setDisplayedChild(tab);
        if (tab == FORM_SIGN_IN) {
            tabSignIn.setBackgroundResource(R.drawable.bg_tab_selected);
            tabSignIn.setTextColor(getResources().getColor(R.color.white, null));
            tabSignUp.setBackgroundResource(R.drawable.bg_tab_unselected);
            tabSignUp.setTextColor(getResources().getColor(R.color.tab_unselected_text, null));
        } else {
            tabSignUp.setBackgroundResource(R.drawable.bg_tab_selected);
            tabSignUp.setTextColor(getResources().getColor(R.color.white, null));
            tabSignIn.setBackgroundResource(R.drawable.bg_tab_unselected);
            tabSignIn.setTextColor(getResources().getColor(R.color.tab_unselected_text, null));
        }
        // Clear errors when switching
        clearErrors();
    }

    private void clearErrors() {
        tvLoginError.setVisibility(View.GONE);
        tvSignupError.setVisibility(View.GONE);
        tilLoginEmail.setError(null);
        tilLoginPassword.setError(null);
        tilSignupEmail.setError(null);
        tilSignupPassword.setError(null);
        tilSignupConfirmPassword.setError(null);
    }

    // ==================== SIGN IN ====================

    private void setupSignInForm() {
        btnSignIn.setOnClickListener(v -> {
            String email = getText(etLoginEmail);
            String password = getText(etLoginPassword);

            // Clear previous errors
            tilLoginEmail.setError(null);
            tilLoginPassword.setError(null);
            tvLoginError.setVisibility(View.GONE);

            if (email.isEmpty()) {
                tilLoginEmail.setError(getString(R.string.user_fill_all));
                return;
            }
            if (password.isEmpty()) {
                tilLoginPassword.setError(getString(R.string.user_fill_all));
                return;
            }

            setLoading(btnSignIn, progressLogin, true);
            doLogin(email, password);
        });

        tvForgotPassword.setOnClickListener(v -> {
            viewFlipper.setDisplayedChild(STATE_FORGOT_PASSWORD);
        });
    }

    private void doLogin(String email, String password) {
        try {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("password", password);

            ApiClient.post("/api/auth/login", body, null, new ApiClient.ApiCallback() {
                @Override
                public void onSuccess(JSONObject response) {
                    setLoading(btnSignIn, progressLogin, false);
                    saveTokens(response);
                    Toast.makeText(getContext(), R.string.user_login_success, Toast.LENGTH_SHORT).show();
                    updateUI();
                }

                @Override
                public void onError(String error) {
                    setLoading(btnSignIn, progressLogin, false);
                    tvLoginError.setText(error);
                    tvLoginError.setVisibility(View.VISIBLE);
                }
            });
        } catch (Exception e) {
            setLoading(btnSignIn, progressLogin, false);
            tvLoginError.setText(e.getMessage());
            tvLoginError.setVisibility(View.VISIBLE);
        }
    }

    // ==================== SIGN UP ====================

    private void setupSignUpForm() {
        // Real-time password validation
        etSignupPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                validateSignupPassword(s.toString(), false);
            }
        });

        etSignupConfirmPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String password = getText(etSignupPassword);
                String confirm = s.toString();
                if (!confirm.isEmpty() && !confirm.equals(password)) {
                    tilSignupConfirmPassword.setError(getString(R.string.user_password_error_mismatch));
                } else {
                    tilSignupConfirmPassword.setError(null);
                }
            }
        });

        btnSignUp.setOnClickListener(v -> {
            String email = getText(etSignupEmail);
            String password = getText(etSignupPassword);
            String confirmPassword = getText(etSignupConfirmPassword);
            String name = getText(etSignupName);

            // Clear previous errors
            tilSignupEmail.setError(null);
            tvSignupError.setVisibility(View.GONE);

            if (email.isEmpty()) {
                tilSignupEmail.setError(getString(R.string.user_fill_all));
                return;
            }
            if (password.isEmpty()) {
                tilSignupPassword.setError(getString(R.string.user_fill_all));
                return;
            }

            // Full password validation on submit
            if (!validateSignupPassword(password, true)) {
                return;
            }

            if (!password.equals(confirmPassword)) {
                tilSignupConfirmPassword.setError(getString(R.string.user_password_error_mismatch));
                return;
            }

            setLoading(btnSignUp, progressSignup, true);
            doSignup(email, password, name);
        });
    }

    /**
     * Validates signup password and shows errors inline via TextInputLayout.
     * @param password The password to validate
     * @param showAllErrors If true, show the first failing validation. If false, only show errors once user types enough.
     * @return true if password is valid
     */
    private boolean validateSignupPassword(String password, boolean showAllErrors) {
        if (password.isEmpty()) {
            tilSignupPassword.setError(null);
            return false;
        }

        if (password.length() < 8) {
            tilSignupPassword.setError(getString(R.string.user_password_error_length));
            return false;
        }

        if (!password.matches(".*[A-Z].*")) {
            tilSignupPassword.setError(getString(R.string.user_password_error_uppercase));
            return false;
        }

        if (!password.matches(".*[a-z].*")) {
            tilSignupPassword.setError(getString(R.string.user_password_error_lowercase));
            return false;
        }

        if (!password.matches(".*\\d.*")) {
            tilSignupPassword.setError(getString(R.string.user_password_error_digit));
            return false;
        }

        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) {
            tilSignupPassword.setError(getString(R.string.user_password_error_special));
            return false;
        }

        // All checks passed
        tilSignupPassword.setError(null);
        return true;
    }

    private void doSignup(String email, String password, String name) {
        try {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("password", password);
            if (!name.isEmpty()) body.put("display_name", name);

            ApiClient.post("/api/auth/register", body, null, new ApiClient.ApiCallback() {
                @Override
                public void onSuccess(JSONObject response) {
                    setLoading(btnSignUp, progressSignup, false);
                    otpEmail = email;
                    showOtpState(email);
                }

                @Override
                public void onError(String error) {
                    setLoading(btnSignUp, progressSignup, false);
                    tvSignupError.setText(error);
                    tvSignupError.setVisibility(View.VISIBLE);
                }
            });
        } catch (Exception e) {
            setLoading(btnSignUp, progressSignup, false);
            tvSignupError.setText(e.getMessage());
            tvSignupError.setVisibility(View.VISIBLE);
        }
    }

    // ==================== OTP ====================

    private void setupOtpForm() {
        btnVerifyOtp.setOnClickListener(v -> {
            String otp = getText(etOtp);
            tvOtpError.setVisibility(View.GONE);

            if (otp.length() != 6) {
                tvOtpError.setText(getString(R.string.user_otp_invalid));
                tvOtpError.setVisibility(View.VISIBLE);
                return;
            }

            setLoading(btnVerifyOtp, progressOtp, true);
            verifyOTP(otpEmail, otp);
        });

        btnOtpBack.setOnClickListener(v -> {
            viewFlipper.setDisplayedChild(STATE_AUTH_FORM);
        });
    }

    private void showOtpState(String email) {
        tvOtpMessage.setText(getString(R.string.user_otp_sent, email));
        etOtp.setText("");
        tvOtpError.setVisibility(View.GONE);
        viewFlipper.setDisplayedChild(STATE_OTP);
    }

    private void verifyOTP(String email, String otp) {
        try {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("otp", otp);

            ApiClient.post("/api/auth/verify-otp", body, null, new ApiClient.ApiCallback() {
                @Override
                public void onSuccess(JSONObject response) {
                    setLoading(btnVerifyOtp, progressOtp, false);
                    saveTokens(response);
                    Toast.makeText(getContext(), R.string.user_signup_success, Toast.LENGTH_SHORT).show();
                    updateUI();
                }

                @Override
                public void onError(String error) {
                    setLoading(btnVerifyOtp, progressOtp, false);
                    tvOtpError.setText(error);
                    tvOtpError.setVisibility(View.VISIBLE);
                }
            });
        } catch (Exception e) {
            setLoading(btnVerifyOtp, progressOtp, false);
        }
    }

    // ==================== FORGOT PASSWORD ====================

    private void setupForgotPasswordForm() {
        btnSendOtp.setOnClickListener(v -> {
            String email = getText(etForgotEmail);
            if (email.isEmpty()) {
                Toast.makeText(getContext(), R.string.user_fill_all, Toast.LENGTH_SHORT).show();
                return;
            }

            setLoading(btnSendOtp, progressForgot, true);
            doForgotPassword(email);
        });

        btnForgotBack.setOnClickListener(v -> {
            viewFlipper.setDisplayedChild(STATE_AUTH_FORM);
        });
    }

    private void doForgotPassword(String email) {
        try {
            JSONObject body = new JSONObject();
            body.put("email", email);

            ApiClient.post("/api/auth/forgot-password", body, null, new ApiClient.ApiCallback() {
                @Override
                public void onSuccess(JSONObject response) {
                    setLoading(btnSendOtp, progressForgot, false);
                    forgotEmail = email;
                    viewFlipper.setDisplayedChild(STATE_RESET_PASSWORD);
                }

                @Override
                public void onError(String error) {
                    setLoading(btnSendOtp, progressForgot, false);
                    Toast.makeText(getContext(), error, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            setLoading(btnSendOtp, progressForgot, false);
        }
    }

    // ==================== RESET PASSWORD ====================

    private void setupResetPasswordForm() {
        btnResetPassword.setOnClickListener(v -> {
            String otp = getText(etResetOtp);
            String newPass = getText(etResetNewPassword);

            if (otp.length() != 6 || newPass.length() < 8) {
                Toast.makeText(getContext(), R.string.user_fill_all, Toast.LENGTH_SHORT).show();
                return;
            }

            setLoading(btnResetPassword, progressReset, true);
            doResetPassword(forgotEmail, otp, newPass);
        });

        btnResetBack.setOnClickListener(v -> {
            viewFlipper.setDisplayedChild(STATE_AUTH_FORM);
        });
    }

    private void doResetPassword(String email, String otp, String newPassword) {
        try {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("otp", otp);
            body.put("new_password", newPassword);

            ApiClient.post("/api/auth/reset-password", body, null, new ApiClient.ApiCallback() {
                @Override
                public void onSuccess(JSONObject response) {
                    setLoading(btnResetPassword, progressReset, false);
                    Toast.makeText(getContext(), R.string.user_password_reset_success, Toast.LENGTH_SHORT).show();
                    viewFlipper.setDisplayedChild(STATE_AUTH_FORM);
                    switchToTab(FORM_SIGN_IN);
                }

                @Override
                public void onError(String error) {
                    setLoading(btnResetPassword, progressReset, false);
                    Toast.makeText(getContext(), error, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            setLoading(btnResetPassword, progressReset, false);
        }
    }

    // ==================== LOGGED IN STATE ====================

    private void setupLoggedInState() {
        btnBuyPremium.setOnClickListener(v -> createPayment());
        btnLogout.setOnClickListener(v -> logout());
    }

    private void showLoggedOutState() {
        viewFlipper.setDisplayedChild(STATE_AUTH_FORM);
        switchToTab(FORM_SIGN_IN);
    }

    private void showLoggedInState(JSONObject user) {
        viewFlipper.setDisplayedChild(STATE_LOGGED_IN);

        try {
            tvUserEmail.setText(user.optString("email", ""));
            String name = user.optString("display_name", "");
            tvUserName.setText(name.isEmpty() || name.equals("null") ? getString(R.string.user_welcome) : name);

            boolean isPremium = user.optBoolean("is_premium", false);
            mPrefs.edit().putBoolean("is_premium", isPremium).apply();
            if (isPremium) {
                tvPremiumStatus.setText(R.string.user_premium_active);
                tvPremiumStatus.setTextColor(0xFF2ED573);
                String expiry = user.optString("premium_expires_at", "");
                if (!expiry.isEmpty() && !expiry.equals("null")) {
                    tvPremiumExpiry.setVisibility(View.VISIBLE);
                    String displayDate = expiry.length() > 10 ? expiry.substring(0, 10) : expiry;
                    tvPremiumExpiry.setText(getString(R.string.user_premium_until, displayDate));
                }
                btnBuyPremium.setVisibility(View.GONE);
            } else {
                tvPremiumStatus.setText(R.string.user_premium_expired);
                tvPremiumStatus.setTextColor(0xFFFF4757);
                tvPremiumExpiry.setVisibility(View.GONE);
                btnBuyPremium.setVisibility(View.VISIBLE);
            }
        } catch (Exception ignored) {}
    }

    // ==================== LANGUAGE SWITCH ====================

    private void setupLanguageSwitch() {
        View.OnClickListener langSwitchListener = v -> {
            String currentLang = mPrefs.getString("app_language", "");
            String newLang = currentLang.equals("vi") ? "en" : "vi";
            mPrefs.edit().putString("app_language", newLang).apply();

            if (getActivity() != null) {
                Intent intent = getActivity().getIntent();
                getActivity().finish();
                startActivity(intent);
            }
        };
        btnSwitchLanguage.setOnClickListener(langSwitchListener);
        btnSwitchLanguageStandalone.setOnClickListener(langSwitchListener);
    }

    // ==================== AUTH STATE ====================

    private void updateUI() {
        String token = mPrefs.getString("auth_token", null);
        if (token != null && !token.isEmpty()) {
            showLoading(true);
            ApiClient.get("/api/auth/me", token, new ApiClient.ApiCallback() {
                @Override
                public void onSuccess(JSONObject response) {
                    showLoading(false);
                    showLoggedInState(response);
                }

                @Override
                public void onError(String error) {
                    showLoading(false);
                    tryRefreshToken();
                }
            });
        } else {
            showLoggedOutState();
        }
    }

    private void tryRefreshToken() {
        String refreshToken = mPrefs.getString("refresh_token", null);
        if (refreshToken == null) {
            showLoggedOutState();
            return;
        }

        try {
            JSONObject body = new JSONObject();
            body.put("refresh_token", refreshToken);
            ApiClient.post("/api/auth/refresh", body, null, new ApiClient.ApiCallback() {
                @Override
                public void onSuccess(JSONObject response) {
                    saveTokens(response);
                    updateUI();
                }

                @Override
                public void onError(String error) {
                    logout();
                }
            });
        } catch (Exception ignored) {
            logout();
        }
    }

    private void showLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    // ==================== PAYMENT ====================

    private void createPayment() {
        if (getContext() == null) return;
        String token = mPrefs.getString("auth_token", null);
        if (token == null) return;

        btnBuyPremium.setEnabled(false);
        ApiClient.post("/api/payment/create", null, token, new ApiClient.ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                btnBuyPremium.setEnabled(true);
                String checkoutUrl = response.optString("checkout_url", "");
                if (!checkoutUrl.isEmpty()) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(checkoutUrl));
                    startActivity(intent);
                }
            }

            @Override
            public void onError(String error) {
                btnBuyPremium.setEnabled(true);
                Toast.makeText(getContext(), error, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ==================== HELPERS ====================

    private void saveTokens(JSONObject response) {
        String accessToken = response.optString("access_token", "");
        String refreshToken = response.optString("refresh_token", "");
        mPrefs.edit()
                .putString("auth_token", accessToken)
                .putString("refresh_token", refreshToken)
                .apply();
    }

    private void logout() {
        mPrefs.edit()
                .remove("auth_token")
                .remove("refresh_token")
                .remove("is_premium")
                .apply();
        showLoggedOutState();
        if (getContext() != null) {
            Toast.makeText(getContext(), R.string.user_logged_out, Toast.LENGTH_SHORT).show();
        }
    }

    private String getText(TextInputEditText editText) {
        return editText.getText() != null ? editText.getText().toString().trim() : "";
    }

    private void setLoading(Button button, ProgressBar progress, boolean loading) {
        button.setEnabled(!loading);
        button.setAlpha(loading ? 0.6f : 1.0f);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateUI();
    }
}
