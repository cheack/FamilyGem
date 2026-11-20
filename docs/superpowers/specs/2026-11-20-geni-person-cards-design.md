# Person cards redesign (small change)

Approved by the user on 2026-11-20 (approach B).

## Intent
The diagram cards should feel closer to a family-tree site: photo on the left, text on the right, colour by sex. The user showed Geni's web tree as the reference.

## Goals
- Horizontal person card: photo (64dp) left, name / title / dates right.
- Sex shown by a 4dp accent stripe on the left edge plus a soft tinted fill (own palette).
- Mourn corner, selected-person glow, partner background, print/export behaviour unchanged.
- Zero upstream file edited: all changes are overlay files in `app/src/geni/res` (debug and release source sets), so `git merge upstream/master` stays cheap.

## Non-goals
Lines, bonds, "add mother" placeholders, "+71" badge, minicards, rounded photos (needs code in upstream's image loader).

## Anti-goals
- No Geni logo, name, colours or gradients on the cards: Geni's branding rules forbid implying endorsement, and the cards are private content.
- No change to `DiagramFragment.kt`. If the layout needs a code change, stop and ask.

## Constraints
- Same view IDs as `diagram_card.xml`: `card_background`, `card_border`, `card_picture`, `card_name`, `card_title`, `card_data`, `card_mourn`. `DiagramFragment.GraphicPerson` binds by these and measures the card at runtime, so sizes follow the layout.
- Night theme must stay readable (`values-night/colors.xml`).

## Approaches
- A. Drawables only, card stays vertical. Cheapest, no photo-left layout.
- B. Overlay layout `diagram_card.xml` plus overlay drawables `person_border_{male,female,undefined}` (layer-list: stripe + tint). Chosen: layout change with no upstream edit.
- C. Custom canvas diagram. Rejected: rewrites upstream, costly merges.

## Left to the builder
Exact colours (light and night), padding, stripe/tint alpha; keep text sizes close to upstream's so long names still wrap with `ElasticTextView`. Check on the Pixel 6 with a long name and a card without photo.

## Same PR audit (Geni branding)
Check the About screen for the "not endorsed by Geni.com" disclaimer, the login screen for the Geni logo and the "Log in with Geni" label. Report gaps; fix in a separate task if large.
