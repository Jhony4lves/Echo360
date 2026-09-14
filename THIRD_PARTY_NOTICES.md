# Third-party notices

Echo360 includes original code informed by public Xbox 360 format documentation and open-source interoperability projects. The following project materially informed the EchoFix GOD/XDVDFS implementation.

## GODSend-360

Project: `ghostyshell/GODSend-360`

Copyright (c) 2026 Nesquin

MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

EchoFix's read-only XDVDFS directory parsing approach and GOD layout validation were cross-checked against GODSend-360's pure-Go implementation. Echo360's Android/Kotlin implementation is maintained separately and is tailored to streaming data from the user's Xbox through the Echo360 FTP abstraction.

## God2Iso

Project: `raburton/god2iso`

God2Iso was consulted as an interoperability reference for legacy GOD reconstruction behavior, including XSF-header presence and optimized-container sector offsets. Echo360 does not bundle God2Iso, its binaries, its XSFHeader resource, or Microsoft Windows API Code Pack files. The EchoFix implementation creates only the minimal temporary layout required for read-only XDVDFS inspection and uses its own Kotlin streaming code.
