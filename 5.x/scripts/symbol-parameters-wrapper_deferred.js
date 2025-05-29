/*
 * Copyright 2014-2024 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

// helps with some corner cases where <wbr> starts working already,
// but the signature is not yet long enough to be wrapped
(function() {
    const leftPaddingPx = 60;

    function createNbspIndent() {
        let indent = document.createElement("span");
        indent.append(document.createTextNode("\u00A0\u00A0\u00A0\u00A0"));
        indent.classList.add("nbsp-indent");
        return indent;
    }

    function wrapSymbolParameters(entry) {
        const symbol = entry.target;
        const symbolBlockWidth = entry.borderBoxSize && entry.borderBoxSize[0] && entry.borderBoxSize[0].inlineSize;

        // Even though the script is marked as `defer` and we wait for `DOMContentLoaded` event,
        // or if this block is a part of hidden tab, it can happen that `symbolBlockWidth` is 0,
        // indicating that something hasn't been loaded.
        // In this case, observer will be triggered onсe again when it will be ready.
        if (symbolBlockWidth > 0) {
            const node = symbol.querySelector(".parameters");

            if (node) {
                // if window resize happened and observer was triggered, reset previously wrapped
                // parameters as they might not need wrapping anymore, and check again
                node.classList.remove("wrapped");
                node.querySelectorAll(".parameter .nbsp-indent")
                    .forEach(indent => indent.remove());

                const innerTextWidth = Array.from(symbol.children)
                    .filter(it => !it.classList.contains("block")) // blocks are usually on their own (like annotations), so ignore it
                    .map(it => it.getBoundingClientRect().width)
                    .reduce((a, b) => a + b, 0);

                // if signature text takes up more than a single line, wrap params for readability
                if (innerTextWidth > (symbolBlockWidth - leftPaddingPx)) {
                    node.classList.add("wrapped");
                    node.querySelectorAll(".parameter").forEach(param => {
                        // has to be a physical indent so that it can be copied. styles like
                        // paddings and `::before { content: "    " }` do not work for that
                        param.prepend(createNbspIndent());
                    });
                }
            }
        }
    }

    const symbolsObserver = new ResizeObserver(entries => entries.forEach(wrapSymbolParameters));

    function initHandlers() {
        document.querySelectorAll("div.symbol").forEach(symbol => symbolsObserver.observe(symbol));
    }

    if (document.readyState === 'loading') window.addEventListener('DOMContentLoaded', initHandlers);
    else initHandlers();

    // ToDo: Add `unobserve` if dokka will be SPA-like:
    //       https://github.com/w3c/csswg-drafts/issues/5155
})();
