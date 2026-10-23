# Manual test guide: card icons, Select button, app icons (Lite 1.3.3, installed 04:07)

Go through it in order. Tick each box. Write on the **Notes** line anything that looked different
from **Expect**. Have a GIF and an ordinary image (PNG/JPG) saved on the Odin before you start.

---

## 1. Android app icons inside a card

1. Open a custom card that has Android apps in it.
- [ ] Expect: each app shows its normal launcher icon. There's no dark square behind it.
- [ ] Expect: an app with no icon still shows its letter on a dark tile.
- Notes:

## 2. Select button opens the category menu

1. On the XMB bar, move to any category (e.g. Game) and press **Select**.
- [ ] Expect: the same menu that long-pressing the category icon gives you.
- [ ] Expect: B closes it.
2. Open a list (e.g. inside a card or a settings page) and press **Select**.
- [ ] Expect: it changes the sort order, as before. No category menu.
3. Turn on mark mode (multi-select) on the XMB and press **Select**.
- [ ] Expect: it changes the sort order. No category menu.
- Notes:

## 3. Card icon: your own image

1. Open a custom card, then **Change Icon**.
- [ ] Expect: a settings page with three groups: From Your Device, From the Applied Theme, and Built-in.
2. **Choose Image…** and pick your PNG/JPG.
- [ ] Expect: the page now shows **Your Image** ("In use") with a preview, **Replace Image…** and **Remove Image**.
- [ ] Expect: back on the XMB, the card shows your image.
3. **Replace Image…** and pick your GIF.
- [ ] Expect: the label reads "Animated · in use". The GIF plays while the cursor is on the card and stops when you move off it.
4. Try picking a file that isn't an image (e.g. a PDF, if the picker lets you).
- [ ] Expect: a short message saying why it can't be used. The old icon stays.
5. **Remove Image**.
- [ ] Expect: the card goes back to its built-in icon.
- Notes:

## 4. Card icon: theme icons

1. Apply a theme that has its own icons, then go back to Change Icon for a card.
- [ ] Expect: the **From the Applied Theme** group lists the theme's icons, each with a preview. There are no repeats of the same picture.
2. Pick one.
- [ ] Expect: the card shows that icon on the XMB.
3. Switch to a different theme.
- [ ] Expect: the card keeps the icon you picked.
4. With a theme that has no icons applied, open Change Icon.
- [ ] Expect: no From the Applied Theme group.
- Notes:

## 5. Card icon: built-in

1. With your own image set, pick a **Built-in** icon.
- [ ] Expect: your image is removed and the built-in icon shows. Only that row says "In use".
- Notes:

## 6. Things that should not lose your image

1. Set an image on a card. Then open the XMB icon editor and use **Reset All**.
- [ ] Expect: the card keeps its image.
2. Restart the launcher (or reboot).
- [ ] Expect: the card keeps its image.
3. Delete the card, then make a new one.
- [ ] Expect: the new card starts with the default icon, not the old image.
- Notes:

## 7. Controller and touch

- [ ] Every row on the Change Icon page can be reached and picked with the D-pad + A.
- [ ] The same rows work by tapping.
- [ ] B / Back leaves the page.
- Notes:

---

## Overall

- [ ] Anything crash? Where:
- [ ] Anything slow (e.g. the theme group taking long to appear)? Where:
- Notes:

If something goes wrong: Settings ▸ System ▸ Logs ▸ share the session log.
