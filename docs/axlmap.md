# Line map format (`*.axlmap`)

Every generated `Foo.java` gets a `Foo.axlmap` next to it. It tells which line of the
`.axl` source produced each line of the Java file. Compiler diagnostics, `axl retrace`
and the SMAP post-processor all read it.

```json
{
  "version": 1,
  "source": "util/Parse.axl",
  "lines": [1, 0, 0, 0, 0, 0, 3, 4, 4]
}
```

| Field     | Meaning |
|-----------|---------|
| `version` | Format version, currently `1`. |
| `source`  | Path of the `.axl` file relative to its source root, with `/` separators. |
| `lines`   | One entry per line of the `.java` file. Entry `i` is the 1-based `.axl` line of Java line `i + 1`, or `0` when the Java line has no source (fixed header lines, the private constructor, closing class brace). |

The array always has exactly as many entries as the Java file has lines. Tools that need
a line for an unmapped Java line use the closest mapped line above it.
