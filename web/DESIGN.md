# Matinee demo: design notes

The page is a **neighbourhood video store after dark**. You browse a shelf, pull tapes, rate them at the counter, and the staff pin up picks for you. The results print out as a till receipt.

## Tokens (defined at the top of `template.html`)

- **Walls:** pale sage green (`#dfe5df`), with deep green-black (`#121614`) when the lights are off. Shelves are wood (`#8a6644`).
- **Type:**
  - Big Shoulders Display, uppercase, for anything printed on a sign or a spine.
  - Archivo for reading.
  - Courier Prime for labels, stickers and the receipt.
- **Aisle colours:** eight colour-coded aisles, one per genre group. These colours mean "which aisle", nothing else.
- **One accent:** price-sticker yellow (`#f2c230`). It marks what you've done: rated-tape stickers, the chosen rating, pick numbers and the headline highlight.
- **Shape:** square edges, round stickers only. Shelf-talkers sit a fraction of a degree off straight, so they look pinned up by hand.

## Things to avoid

- Any real rental chain's logo, colours or ticket shape.
- Cover art (it isn't ours to use).
- Neon glow.
- Gradients on the surfaces.
- A second sticker colour.

## Voice

Shop-floor plain: "Pull a few tapes", "Ask for it", "Not in stock". The receipt and notes say exactly what was measured.
