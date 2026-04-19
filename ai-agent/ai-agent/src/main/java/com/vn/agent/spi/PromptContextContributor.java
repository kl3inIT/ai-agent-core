package com.vn.agent.spi;

/**
 * Host extension point for appending <b>domain-specific</b> text into the system prompt
 * at request time. Fragments are concatenated in ascending {@link #getOrder()} value after
 * the add-on's base system prompt.
 *
 * <p><b>Baseline is built-in — do NOT re-derive it.</b> The add-on's base system prompt
 * already conveys current user identity, roles, and locale; host fragments MUST NOT
 * duplicate that plumbing. This SPI exists only for genuinely domain-specific text:
 * business vocabulary, house rules, tone/voice instructions, product-specific
 * constraints, etc.</p>
 *
 * <p><b>Examples of appropriate use:</b>
 * <ul>
 *   <li>"Refer to customers as 'members'; refer to orders as 'bookings'."</li>
 *   <li>"Never suggest discounts above 15% without citing an explicit promo code."</li>
 *   <li>"When summarising cases, use the STAR format."</li>
 * </ul>
 *
 * <p><b>Do NOT use for:</b> "The current user is {@code ${username}}" / "User roles: {@code ...}"
 * — those are already in the base prompt.</p>
 */
public interface PromptContextContributor {
    /** @return text to append; empty string = no contribution. Avoid trailing newlines. */
    String fragment();

    /** Lower values placed earlier in the concatenated prompt. Default 0. */
    default int getOrder() { return 0; }
}
