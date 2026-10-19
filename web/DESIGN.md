# Matinee demo: design notes

The page is an **old cinema marquee**, matching the project thumbnail (`.github/thumbnail.jpg`). A red marquee with a peaked top and rows of bulbs, a cream changeable-letter board that says what's showing, and an ADMIT ONE ticket. You still browse the shelf, rate titles at the counter, and the picks slide onto the marquee board.

## Themes

- **Night (dark):** the thumbnail itself. Near-black sky (`#140c10`), lit bulbs (`#ffd36e`) with soft halos that breathe slowly.
- **Daytime (light):** the same cinema in the afternoon. Warm paper ground (`#f3ead8`), the same red marquee, ink letters, bulbs switched off (pale glass, no halo).
- The marquee red (`#7a1020`), the letter board (`#fbf6ea` / `#f6efe1`), the ticket yellow and the MATINEE sign stay the same in both. The theme button says "Lights off" / "Lights on".

## Tokens (defined at the top of `template.html`)

- **Type** (SIL OFL, self-hosted in `docs/fonts/`):
  - National Park, widely letterspaced capitals, for the sign, the letter board, headings, labels and tickets. Regular weight for reading.
  - Instrument Serif italic for the asides: the tagline, "because you rated" lines, hints and small links.
- **Red** (`#7a1020`): the marquee, the strips the titles stand on, pick numbers.
- **Bulb yellow** (`#ffd36e`): the MATINEE sign, lit bulbs, tickets and the chosen aisle or rating.
- **Aisle colours:** eight colour-coded aisles, one per genre group. They only mean "which aisle".
- **Shape:** square edges. Tickets have round notches cut out of their sides and a dashed tear line.

## Things to avoid

- Any real cinema chain's logo or ticket design.
- Cover art (it isn't ours to use).
- Gradients on surfaces (the bulb halos are the only glow).
- Bulb animation when reduced motion is requested.

## Voice

Shop-floor plain: "Pull a few tapes", "Ask for it", "Not in stock". The box-office slip and notes say exactly what was measured.
