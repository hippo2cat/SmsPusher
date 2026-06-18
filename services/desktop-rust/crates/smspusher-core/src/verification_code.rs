use regex::Regex;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VerificationCodeConfidence {
    High,
    Low,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerificationCode {
    pub value: String,
    pub confidence: VerificationCodeConfidence,
}

pub struct VerificationCodeExtractor;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CandidateKind {
    Numeric,
    Alphanumeric,
}

#[derive(Debug, Clone)]
struct Candidate {
    value: String,
    start: usize,
    end: usize,
    kind: CandidateKind,
}

impl VerificationCodeExtractor {
    pub fn extract(body: &str) -> Option<VerificationCode> {
        let candidates = code_candidates(body);
        if candidates.is_empty() {
            return None;
        }

        let keywords = keyword_ranges(body);
        if !keywords.is_empty() {
            return best_keyword_candidate(&candidates, &keywords).map(|candidate| {
                VerificationCode {
                    value: candidate.value.clone(),
                    confidence: VerificationCodeConfidence::High,
                }
            });
        }

        let low_confidence_numeric = candidates
            .iter()
            .filter(|candidate| {
                candidate.kind == CandidateKind::Numeric && candidate.value.len() <= 6
                    && !has_contact_context(body, candidate)
            })
            .collect::<Vec<_>>();
        let unique_values = low_confidence_numeric
            .iter()
            .map(|candidate| candidate.value.as_str())
            .collect::<std::collections::BTreeSet<_>>();
        if unique_values.len() == 1 {
            return low_confidence_numeric
                .first()
                .map(|candidate| VerificationCode {
                    value: candidate.value.clone(),
                    confidence: VerificationCodeConfidence::Low,
                });
        }
        None
    }
}

fn best_keyword_candidate<'a>(
    candidates: &'a [Candidate],
    keywords: &[(usize, usize)],
) -> Option<&'a Candidate> {
    candidates
        .iter()
        .filter_map(|candidate| {
            keywords
                .iter()
                .map(|keyword| range_distance(*keyword, (candidate.start, candidate.end)))
                .min()
                .filter(|distance| *distance <= 80)
                .map(|distance| (candidate, distance))
        })
        .min_by(|left, right| {
            left.1
                .cmp(&right.1)
                .then_with(|| left.0.start.cmp(&right.0.start))
        })
        .map(|(candidate, _)| candidate)
}

fn keyword_ranges(body: &str) -> Vec<(usize, usize)> {
    let lower = body.to_lowercase();
    let terms = [
        "验证码",
        "校验码",
        "动态码",
        "登录码",
        "verification code",
        "security code",
        "passcode",
        "otp",
        "code",
    ];
    let mut ranges = Vec::new();
    for term in terms {
        let mut offset = 0;
        while let Some(found) = lower[offset..].find(term) {
            let start = offset + found;
            let end = start + term.len();
            ranges.push((start, end));
            offset = end;
        }
    }
    ranges
}

fn code_candidates(body: &str) -> Vec<Candidate> {
    let numeric = Regex::new(r"(?:[0-9][ -]?){4,8}").expect("numeric regex");
    let alphanumeric = Regex::new(r"[A-Za-z0-9]{4,8}").expect("alphanumeric regex");
    let mut candidates = Vec::new();

    for result in numeric.find_iter(body) {
        let raw = result.as_str().trim_end_matches([' ', '-']);
        let end = result.start() + raw.len();
        if !has_token_boundary(body, result.start(), end) {
            continue;
        }
        let value = raw
            .chars()
            .filter(|character| character.is_ascii_digit())
            .collect::<String>();
        if (4..=8).contains(&value.len()) {
            candidates.push(Candidate {
                value,
                start: result.start(),
                end,
                kind: CandidateKind::Numeric,
            });
        }
    }

    for result in alphanumeric.find_iter(body) {
        if !has_token_boundary(body, result.start(), result.end()) {
            continue;
        }
        let raw = result.as_str();
        if raw.chars().any(|character| character.is_ascii_alphabetic())
            && raw.chars().any(|character| character.is_ascii_digit())
        {
            candidates.push(Candidate {
                value: raw.to_owned(),
                start: result.start(),
                end: result.end(),
                kind: CandidateKind::Alphanumeric,
            });
        }
    }

    let mut seen = std::collections::BTreeSet::new();
    candidates
        .into_iter()
        .filter(|candidate| seen.insert((candidate.start, candidate.end, candidate.value.clone())))
        .collect()
}

fn has_contact_context(body: &str, candidate: &Candidate) -> bool {
    let before = body[..candidate.start]
        .chars()
        .rev()
        .take(12)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect::<String>();
    let after = body[candidate.end..].chars().take(6).collect::<String>();
    let context = format!("{before}{after}").to_lowercase();
    [
        "详询",
        "客服",
        "热线",
        "电话",
        "咨询",
        "致电",
        "拨打",
        "call",
        "hotline",
        "support",
    ]
    .iter()
    .any(|term| context.contains(term))
}

fn has_token_boundary(body: &str, start: usize, end: usize) -> bool {
    let before = body[..start].chars().next_back();
    let after = body[end..].chars().next();
    !before.is_some_and(is_ascii_word) && !after.is_some_and(is_ascii_word)
}

fn is_ascii_word(character: char) -> bool {
    character.is_ascii_alphanumeric()
}

fn range_distance(first: (usize, usize), second: (usize, usize)) -> usize {
    if first.1 < second.0 {
        second.0 - first.1
    } else if second.1 < first.0 {
        first.0 - second.1
    } else {
        0
    }
}
