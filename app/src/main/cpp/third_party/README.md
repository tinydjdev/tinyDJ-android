# Vendored decoders

The native audio engine decodes FLAC and MP3 to PCM using two single-header,
public-domain libraries from [mackron/dr_libs](https://github.com/mackron/dr_libs):

- `dr_flac.h`
- `dr_mp3.h`

These are **not committed** (see `.gitignore`). Fetch them once before building:

```sh
./app/src/main/cpp/third_party/download_dr_libs.sh
```

Both are in the public domain (or MIT-0 at your option), so vendoring them is fine.
