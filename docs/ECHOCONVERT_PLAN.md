# EchoConvert

EchoConvert is the format-conversion and installation-repair layer for Echo360.

## Immediate MVP

The first production path is deliberately narrow because it solves a real console problem while establishing reusable readers:

- Detect Xbox 360 Games-on-Demand containers created from stock expansion/install discs.
- Download the GoD header and `DataNNNN` parts through the configured EchoTransfer FTP route.
- Validate the GoD header and SHA-1 hash-tree chain.
- Expose the embedded XDVDFS stream as a seekable virtual source without writing an intermediate ISO.
- Extract only the required content package from the install disc.
- Upload it to the correct Xbox Content path and verify the remote size.
- Preserve the original source container; no destructive remote cleanup is performed automatically.

### First supported repair: Dark Souls II Scholar of the First Sin

- Disc 2 Media ID: `0C94D453`
- GodStix container currently observed: `FFED2000/00007000/C0B2D76914692611985C`
- Source on the stock installer disc: `content/0000000000000000/FFED2000/FFFFFFFF/D4B91B6B4DA1509C280F56F77B09203DE7D39AE646`
- Correct HDD destination: `Hdd1:/Content/0000000000000000/465307E4/00000002/D4B91B6B4DA1509C280F56F77B09203DE7D39AE646`

The runtime detects the correct container primarily by Media ID, using the observed GodStix container name only as a fallback.

## Architecture

`GodVirtualStream` is the first EchoConvert format reader. It maps the interleaved GoD hash tables and payload blocks into a verified, seekable virtual byte stream. `XdvdfsReader` consumes that stream directly, so a `GoD -> extracted content` operation does not need a temporary ISO.

This becomes the pattern for the broader converter: formats should be connected through readers/writers and streaming pivots rather than by blindly materializing every intermediate representation.

## Planned conversion graph

- ISO/XDVDFS -> GoD
- GoD -> ISO/XDVDFS
- ISO/XDVDFS -> extracted/XEX directory
- extracted/XEX directory -> ISO/XDVDFS
- GoD -> extracted/XEX directory
- extracted/XEX directory -> GoD
- STFS inspection/extraction for DLC, XBLA and title updates
- install-disc profiles for known stock expansion installers
- generic EchoDoctor detection of incorrectly installed multi-disc content

## Safety

- Read-only detection before mutation.
- Verify source structures before extracting.
- Verify transferred file size after upload.
- Never delete the Xbox source container automatically.
- Temporary Android files are removed after each run.
