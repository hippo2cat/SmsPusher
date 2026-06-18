use smspusher_core::{VerificationCodeConfidence, VerificationCodeExtractor};

#[test]
fn extracts_high_confidence_numeric_code_near_chinese_keyword() {
    let code = VerificationCodeExtractor::extract("您的验证码是 123456，5 分钟内有效").unwrap();

    assert_eq!(code.value, "123456");
    assert_eq!(code.confidence, VerificationCodeConfidence::High);
}

#[test]
fn extracts_high_confidence_alphanumeric_code_near_english_keyword() {
    let code = VerificationCodeExtractor::extract("Your verification code is A1B2C3.").unwrap();

    assert_eq!(code.value, "A1B2C3");
    assert_eq!(code.confidence, VerificationCodeConfidence::High);
}

#[test]
fn extracts_low_confidence_single_numeric_code_without_keyword() {
    let code = VerificationCodeExtractor::extract("Use 246810 to sign in.").unwrap();

    assert_eq!(code.value, "246810");
    assert_eq!(code.confidence, VerificationCodeConfidence::Low);
}

#[test]
fn ignores_multiple_numeric_candidates_without_keyword() {
    assert!(VerificationCodeExtractor::extract("Order 1234 total 5678").is_none());
}

#[test]
fn ignores_customer_service_number_in_non_code_security_notice() {
    let body = "【某银行】安全提示：若非本人操作，请进入 App 查询并解绑相关协议。详询95599。";

    assert!(VerificationCodeExtractor::extract(body).is_none());
}
