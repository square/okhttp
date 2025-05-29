/*
 * Copyright 2014-2024 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

navigationPageText = fetch(pathToRoot + "navigation.html").then(response => response.text())

displayNavigationFromPage = () => {
    navigationPageText.then(data => {
        document.getElementById("sideMenu").innerHTML = data;
    }).then(() => {
        document.querySelectorAll(".toc--row > a").forEach(link => {
            link.setAttribute("href", pathToRoot + link.getAttribute("href"));
        })
    }).then(() => {
        document.querySelectorAll(".toc--part").forEach(nav => {
            if (!nav.classList.contains("toc--part_hidden"))
                nav.classList.add("toc--part_hidden")
        })
    }).then(() => {
        revealNavigationForCurrentPage()
    }).then(() => {
        scrollNavigationToSelectedElement()
    })
    document.querySelectorAll('.footer a[href^="#"]').forEach(anchor => {
        anchor.addEventListener('click', function (e) {
            e.preventDefault();
            document.querySelector(this.getAttribute('href')).scrollIntoView({
                behavior: 'smooth'
            });
        });
    });
}

revealNavigationForCurrentPage = () => {
    let pageId = document.getElementById("content").attributes["pageIds"].value.toString();
    let parts = document.querySelectorAll(".toc--part");
    let found = 0;
    do {
        parts.forEach(part => {
            if (part.attributes['pageId'].value.indexOf(pageId) !== -1 && found === 0) {
                found = 1;
                if (part.classList.contains("toc--part_hidden")) {
                    part.classList.remove("toc--part_hidden");
                    part.setAttribute('data-active', "");
                }
                revealParents(part)
            }
        });
        pageId = pageId.substring(0, pageId.lastIndexOf("/"))
    } while (pageId.indexOf("/") !== -1 && found === 0)
};
revealParents = (part) => {
    if (part.classList.contains("toc--part")) {
        if (part.classList.contains("toc--part_hidden"))
            part.classList.remove("toc--part_hidden");
        revealParents(part.parentNode)
    }
};

scrollNavigationToSelectedElement = () => {
    let selectedElement = document.querySelector('div.toc--part[data-active]')
    if (selectedElement == null) { // nothing selected, probably just the main page opened
        return
    }

    let hasIcon = selectedElement.querySelectorAll(":scope > div.toc--row span.toc--icon").length > 0

    // for an instance enums also have children and are expandable but are not package/module elements
    let isPackageElement = selectedElement.children.length > 1 && !hasIcon
    if (isPackageElement) {
        // if a package is selected or linked, it makes sense to align it to top
        // so that you can see all the members it contains
        selectedElement.scrollIntoView(true)
    } else {
        // if a member within a package is linked, it makes sense to center it since it,
        // this should make it easier to look at surrounding members
        selectedElement.scrollIntoView({
            behavior: 'auto',
            block: 'center',
            inline: 'center'
        })
    }
}

/*
    This is a work-around for safari being IE of our times.
    It doesn't fire a DOMContentLoaded, presumably because eventListener is added after it wants to do it
*/
if (document.readyState === 'loading') {
    window.addEventListener('DOMContentLoaded', () => {
        displayNavigationFromPage()
    })
} else {
    displayNavigationFromPage()
}
