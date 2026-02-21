//! Input validation and sanitization.
//!
//! Ported from `lib/validation.js`.

/// Sanitize text for safe display.
pub fn sanitize_text(text: &str) -> String {
    text.chars()
        .filter(|&c| {
            // Remove null bytes and control characters except newlines and tabs
            !matches!(c, '\0' | '\x01'..='\x08' | '\x0B' | '\x0C' | '\x0E'..='\x1F' | '\x7F')
        })
        .collect()
}

/// Escape HTML entities.
pub fn escape_html(text: &str) -> String {
    let mut escaped = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '&' => escaped.push_str("&amp;"),
            '<' => escaped.push_str("&lt;"),
            '>' => escaped.push_str("&gt;"),
            '"' => escaped.push_str("&quot;"),
            '\'' => escaped.push_str("&#x27;"),
            '/' => escaped.push_str("&#x2F;"),
            '`' => escaped.push_str("&#x60;"),
            '=' => escaped.push_str("&#x3D;"),
            _ => escaped.push(c),
        }
    }
    escaped
}

/// Strip HTML tags.
pub fn strip_html_tags(html: &str) -> String {
    let mut result = String::with_capacity(html.len());
    let mut in_tag = false;
    for c in html.chars() {
        if c == '<' {
            in_tag = true;
        } else if c == '>' {
            in_tag = false;
        } else if !in_tag {
            result.push(c);
        }
    }
    result
}
