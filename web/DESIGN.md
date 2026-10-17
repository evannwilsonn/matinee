# Matinee demo: design notes

The page should feel like a **repertory cinema's printed programme**: a listing you read down, not an app you operate.

## Tokens (defined at the top of `template.html`)

- **Neutrals:** cool grey paper (`#eceef0`) and near-black ink. Dark mode is a projection-booth near-black (`#121214`).
- **Corners:** 0px everywhere.
- **Type:**
  - Newsreader for display, film titles and the italic years.
  - Atkinson Hyperlegible for the interface and body text.
- **One accent:** oxblood (`#7d1a26` light, `#e0717c` dark). It appears in the rank numerals, the stars you've given, and the deployed model's row of the results table. Nothing else gets it.
- **One signature:** large italic serif numerals running down the margin of the picks, the way a programme numbers its screenings. Don't add a second one.

## Things to avoid

- Film-strip or popcorn imagery.
- Card shadows.
- Pill buttons.
- Uppercase eyebrow labels.
- A second accent colour.
- Poster images (they aren't ours to use).

## Voice

Plain and specific. Say what the model did ("Mostly because you rated…"), and say plainly what it can't know.
