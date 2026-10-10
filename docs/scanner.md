# Document scanner

Photograph a page and get a straight, clean scan to send to Grok, share or save. It works
without Google Play services, ML Kit or OpenCV.

## Scanning a page

Tap the scan icon in the top bar and pick a source:

- **Take a photo:** a quick capture with your phone's camera.
- **Choose a photo:** use a picture you already have. Shooting the page with your full
  camera app first, then choosing that photo, gives the sharpest scans: some phones (Pixels
  among them) skip their multi-frame processing in quick capture, so small print comes out
  soft in dim light.

For a sharp scan, fill the frame with the page and use good light. The scanner shows what
it's working with, for example `Photo 4080 × 3072 · page about 2100 × 2900 px`, so you can
see whether to move closer.

## The aligned page

When the page is found, you see it cut out, straightened and cleaned up. Pinch to zoom and
check the small print.

| Button | Does |
| --- | --- |
| **To prompt** | Attaches the page to your message (at up to 2048 px, plenty for Grok to read small print) |
| **Share** | Sends the full-detail page to any app: Signal, Telegram, email… |
| **Save** | Puts the full-detail page in your gallery, in Pictures/Pocket Martian (Android 10 and later) |
| **Enhance: on/off** | Turns the clean-up on or off for this page |
| **Rotate left / right** | Turns the page a quarter turn |
| **Adjust corners** | Back to the outline, to move the corners yourself |

Save and Share leave the scanner open, so one scan can be kept, sent and attached.

In the outline view you can drag any corner, tap **Snap** to pull the corners onto the
nearest paper edges, or **Ask Grok** to find the page. **Align** then makes the page again.

## Privacy and cost

Most scans never leave the phone until you send them. Only when Grok is asked to find the
page does a reduced copy of the photo (about 1024 px) go to xAI, billed like any small
image prompt.

## How it works

1. **Find the page, on the phone.** The photo is reduced and smoothed until texture and
   print drop out. It is then split into lighter and darker regions, and a region's outline
   is reduced to four corners. Each side must lie on a real paper edge, or along the photo's
   border where the page runs out of frame.
   - **Colour:** paper is colourless, so the page is looked for first in a view of the photo
     where colourful surfaces such as wood, skin and cloth go dark. Plain brightness is the
     second opinion.
   - **Edges:** pages that look like their surroundings (a full-colour cover on a busy
     desk) are found by their straight edges alone.
   - **Repairs:** a side blocked by a hand or a second sheet is searched for rather than
     given up on.
   - **Off-frame sides:** a side that runs out of frame is rebuilt parallel to the side
     opposite.
2. **Ask Grok when unsure,** for example white paper on white marble.
3. **Tighten the edges.** The rough outline is walked along each side. Where the paper
   begins is found at dozens of points, and a straight line is fitted through them. The
   comparison uses the median brightness either side, which ignores print, wood grain and
   carpet pile. Points that disagree, such as a finger or a folded corner, are dropped.
4. **Level to the print.** Paper edges can mislead, and printers rarely lay text perfectly
   square. So on print, the slant of the lines of text is measured near the top and bottom
   of the page, and the outline is turned to match. Photos and drawings are left as the
   edges had them.
5. **Flatten from the original photo.** Android's perspective transform maps the page to an
   upright rectangle. It is cut from the photo as stored, at up to 3508 px on the long side
   (A4 at 300 dpi), never enlarged. The page's true proportions are worked out from the
   outline and a phone camera's typical focal length, so a page photographed at an angle
   doesn't come out squat.
6. **Clean up print.** On a page that is mostly paper, the paper's colour is estimated
   everywhere and divided out, which removes shading and colour casts and makes the paper
   white. Brightness is then sharpened and the ink taken to black, and colour is smoothed so
   pen strokes keep their colour without speckle. A page that is mostly pictures is left as
   shot, and so is anything whose main colour paper can't have, such as a coloured card or
   cover. Large coloured areas on a white page, such as logos and highlighter, keep their
   colour.
7. **Trim the edges.** The page is cut just inside the outline. On enhanced pages, any
   sliver of desk left along an edge is whitened, within a narrow band that never reaches
   the print.
