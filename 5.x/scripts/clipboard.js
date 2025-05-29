/*
 * Copyright 2014-2024 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

window.addEventListener('load', () => {
    document.querySelectorAll('span.copy-icon').forEach(element => {
        element.addEventListener('click', (el) => copyElementsContentToClipboard(element));
    })

    document.querySelectorAll('span.anchor-icon').forEach(element => {
        element.addEventListener('click', (el) => {
            if(element.hasAttribute('pointing-to')){
                const location = hrefWithoutCurrentlyUsedAnchor() + '#' + element.getAttribute('pointing-to')
                copyTextToClipboard(element, location)
            }
        });
    })
})

const copyElementsContentToClipboard = (element) => {
    const selection = window.getSelection();
    const range = document.createRange();
    range.selectNodeContents(element.parentNode.parentNode);
    selection.removeAllRanges();
    selection.addRange(range);

    copyAndShowPopup(element,  () => selection.removeAllRanges())
}

const copyTextToClipboard = (element, text) => {
    var textarea = document.createElement("textarea");
    textarea.textContent = text;
    textarea.style.position = "fixed";
    document.body.appendChild(textarea);
    textarea.select();

    copyAndShowPopup(element, () => document.body.removeChild(textarea))
}

const copyAndShowPopup = (element, after) => {
    try {
        document.execCommand('copy');
        element.nextElementSibling.classList.add('active-popup');
        setTimeout(() => {
            element.nextElementSibling.classList.remove('active-popup');
        }, 1200);
    } catch (e) {
        console.error('Failed to write to clipboard:', e)
    }
    finally {
        if(after) after()
    }
}

const hrefWithoutCurrentlyUsedAnchor = () => window.location.href.split('#')[0]

