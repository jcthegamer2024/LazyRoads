# LazyRoads

Smooth road slopes and add dashed center lines with FastAsyncWorldEdit (FAWE) on Paper 26.3.

## Setup

Install [FAWE 2.16.2](https://github.com/IntellectualSites/FastAsyncWorldEdit/releases/tag/2.16.2) and put `LazyRoads-1.2-BETA.jar` in the server's `plugins` folder. If upgrading from FAWERoadAssistant, replace the old JAR. LazyRoads copies your existing config on first start if its own config is missing.

For a local test server, run the Gradle `runServer` task. It loads LazyRoads, FAWE, and AxiomPaper.

## Commands

Use the FAWE wand (`//wand`) to select the road. Choose `//sel cuboid` for a rectangle or `//sel poly` to trace a tighter outline around bends. In polygon mode, left-click the first vertex and right-click each additional vertex. The lowest and highest selected vertices set the vertical bounds.

Include the full road width, the curbs and sidewalks you want to smooth, and the terraces on both sides of the slope. Both commands honor the polygon outline. The selection's bounding box can span up to two million blocks.

| Command | What it does |
| --- | --- |
| `//roadsmooth preview` | Count the proposed smoothing changes. |
| `//roadsmooth` | Add half-block transitions and align the curbs and sidewalks. |
| `//roaddash preview` | Count the proposed center markings. |
| `//roaddash` | Mark the center of an unmarked polished-deepslate road with alternating white dashes and blank gaps. The marks follow the existing slab heights. |
| `//undo` | Revert your last FAWE edit. |
| `/lazyroads reload` | Reload the plugin configuration. |

For dashes on a newly smoothed road, run `//roadsmooth` first and then `//roaddash`. Existing white concrete markings preserve that connected stretch.

When smoothing a T-junction or crossing, include the shared pavement and enough of each approach for LazyRoads to identify where the roads split. It uses the junction to limit how far a transition spreads from a sloped approach into the adjoining roads.

Operators can use both commands by default. Permission nodes are `lazyroads.smooth`, `lazyroads.dash`, and `lazyroads.reload`.

## Dash lengths

In `plugins/LazyRoads/config.yml`, set how many blocks to paint and how many to leave blank. Older configs can add this section. Missing settings default to three blocks.

```yaml
dashes:
  dash-length: 3
  gap-length: 3
```

Use positive whole numbers. Save the file and run `/lazyroads reload`. New marking commands use the updated pattern.
