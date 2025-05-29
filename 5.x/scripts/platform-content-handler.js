/*
 * Copyright 2014-2024 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
/** When Dokka is viewed via iframe, local storage could be inaccessible (see https://github.com/Kotlin/dokka/issues/3323)
 * This is a wrapper around local storage to prevent errors in such cases
 * */
const safeLocalStorage = (() => {
    let isLocalStorageAvailable = false;
    try {
        const testKey = '__testLocalStorageKey__';
        localStorage.setItem(testKey, testKey);
        localStorage.removeItem(testKey);
        isLocalStorageAvailable = true;
    } catch (e) {
        console.error('Local storage is not available', e);
    }

    return {
        getItem: (key) => {
            if (!isLocalStorageAvailable) {
                return null;
            }
            return localStorage.getItem(key);
        },
        setItem: (key, value) => {
            if (!isLocalStorageAvailable) {
                return;
            }
            localStorage.setItem(key, value);
        }
    };
})();

filteringContext = {
    dependencies: {},
    restrictedDependencies: [],
    activeFilters: []
}
let highlightedAnchor;
let topNavbarOffset;
let instances = [];
let sourcesetNotification;

const samplesDarkThemeName = 'darcula'
const samplesLightThemeName = 'idea'

window.addEventListener('load', () => {
    document.querySelectorAll("div[data-platform-hinted]")
        .forEach(elem => elem.addEventListener('click', (event) => togglePlatformDependent(event, elem)))
    const filterSection = document.getElementById('filter-section')
    if (filterSection) {
        filterSection.addEventListener('click', (event) => filterButtonHandler(event))
        initializeFiltering()
    }
    if (typeof initTabs === 'function') {
        initTabs() // initTabs comes from ui-kit/tabs
    }
    handleAnchor()
    topNavbarOffset = document.getElementById('navigation-wrapper')
    darkModeSwitch()
})

const darkModeSwitch = () => {
    const localStorageKey = "dokka-dark-mode"
    const storage = safeLocalStorage.getItem(localStorageKey)
    const osDarkSchemePreferred = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
    const darkModeEnabled = storage ? JSON.parse(storage) : osDarkSchemePreferred
    const element = document.getElementById("theme-toggle-button")
    initPlayground(darkModeEnabled ? samplesDarkThemeName : samplesLightThemeName)

    element.addEventListener('click', () => {
        const enabledClasses = document.getElementsByTagName("html")[0].classList
        enabledClasses.toggle("theme-dark")

        //if previously we had saved dark theme then we set it to light as this is what we save in local storage
        const darkModeEnabled = enabledClasses.contains("theme-dark")
        if (darkModeEnabled) {
            initPlayground(samplesDarkThemeName)
        } else {
            initPlayground(samplesLightThemeName)
        }
        safeLocalStorage.setItem(localStorageKey, JSON.stringify(darkModeEnabled))
    })
}

const initPlayground = (theme) => {
    if (!samplesAreEnabled()) return
    instances.forEach(instance => instance.destroy())
    instances = []

    // Manually tag code fragments as not processed by playground since we also manually destroy all of its instances
    document.querySelectorAll('code.runnablesample').forEach(node => {
        node.removeAttribute("data-kotlin-playground-initialized");
    })

    KotlinPlayground('code.runnablesample', {
        getInstance: playgroundInstance => {
            instances.push(playgroundInstance)
        },
        theme: theme
    });
}

// We check if type is accessible from the current scope to determine if samples script is present
// As an alternative we could extract this samples-specific script to new js file but then we would handle dark mode in 2 separate files which is not ideal
const samplesAreEnabled = () => {
    try {
        if (typeof KotlinPlayground === 'undefined') {
            // KotlinPlayground is exported universally as a global variable or as a module
            // Due to possible interaction with other js scripts KotlinPlayground may not be accessible directly from `window`, so we need an additional check
            KotlinPlayground = exports.KotlinPlayground;
        }
        return typeof KotlinPlayground === 'function';
    } catch (e) {
        return false
    }
}

// Hash change is needed in order to allow for linking inside the same page with anchors
// If this is not present user is forced to refresh the site in order to use an anchor
window.onhashchange = handleAnchor

function scrollToElementInContent(element) {
    const scrollToElement = () => document.getElementById('main').scrollTo({
        top: element.offsetTop - topNavbarOffset.offsetHeight,
        behavior: "smooth"
    })

    const waitAndScroll = () => {
        setTimeout(() => {
            if (topNavbarOffset) {
                scrollToElement()
            } else {
                waitForScroll()
            }
        }, 50)
    }

    if (topNavbarOffset) {
        scrollToElement()
    } else {
        waitAndScroll()
    }
}


function handleAnchor() {
    if (highlightedAnchor) {
        highlightedAnchor.classList.remove('anchor-highlight')
        highlightedAnchor = null;
    }

    let searchForContentTarget = function (element) {
        if (element && element.hasAttribute) {
            if (element.hasAttribute("data-togglable")) return element.getAttribute("data-togglable");
            else return searchForContentTarget(element.parentNode)
        } else return null
    }

    let findAnyTab = function (target) {
    	let result = null
        document.querySelectorAll('div[tabs-section] > button[data-togglable]')
        .forEach(node => {
            if(node.getAttribute("data-togglable").split(",").includes(target)) {
            	result = node
            }
        })
        return result
    }

    let anchor = window.location.hash
    if (anchor !== "") {
        anchor = anchor.substring(1)
        let element = document.querySelector('a[data-name="' + anchor + '"]')

        if (element) {
            const content = element.nextElementSibling
            const contentStyle = window.getComputedStyle(content)
            if(contentStyle.display === 'none') {
		 let tab = findAnyTab(searchForContentTarget(content))
		 if (tab) {
		     toggleSections(tab) // toggleSections comes from ui-kit/tabs
		 }
            }

            if (content) {
                content.classList.add('anchor-highlight')
                highlightedAnchor = content
            }

            scrollToElementInContent(element)
        }
    }
}

function filterButtonHandler(event) {
    if (event.target.tagName === "BUTTON" && event.target.hasAttribute("data-filter")) {
        let sourceset = event.target.getAttribute("data-filter")
        if (filteringContext.activeFilters.indexOf(sourceset) !== -1) {
            filterSourceset(sourceset)
        } else {
            unfilterSourceset(sourceset)
        }
    }
}

function initializeFiltering() {
    filteringContext.dependencies = JSON.parse(sourceset_dependencies)
    document.querySelectorAll("#filter-section > button")
        .forEach(p => filteringContext.restrictedDependencies.push(p.getAttribute("data-filter")))
    Object.keys(filteringContext.dependencies).forEach(p => {
        filteringContext.dependencies[p] = filteringContext.dependencies[p]
            .filter(q => -1 !== filteringContext.restrictedDependencies.indexOf(q))
    })
    let cached = safeLocalStorage.getItem('inactive-filters')
    if (cached) {
        let parsed = JSON.parse(cached)
        filteringContext.activeFilters = filteringContext.restrictedDependencies
            .filter(q => parsed.indexOf(q) === -1)
    } else {
        filteringContext.activeFilters = filteringContext.restrictedDependencies
    }
    refreshFiltering()
}

function filterSourceset(sourceset) {
    filteringContext.activeFilters = filteringContext.activeFilters.filter(p => p !== sourceset)
    refreshFiltering()
    addSourcesetFilterToCache(sourceset)
}

function unfilterSourceset(sourceset) {
    if (filteringContext.activeFilters.length === 0) {
        filteringContext.activeFilters = filteringContext.dependencies[sourceset].concat([sourceset])
        refreshFiltering()
        filteringContext.dependencies[sourceset].concat([sourceset]).forEach(p => removeSourcesetFilterFromCache(p))
    } else {
        filteringContext.activeFilters.push(sourceset)
        refreshFiltering()
        removeSourcesetFilterFromCache(sourceset)
    }

}

function addSourcesetFilterToCache(sourceset) {
    let cached = safeLocalStorage.getItem('inactive-filters')
    if (cached) {
        let parsed = JSON.parse(cached)
        safeLocalStorage.setItem('inactive-filters', JSON.stringify(parsed.concat([sourceset])))
    } else {
        safeLocalStorage.setItem('inactive-filters', JSON.stringify([sourceset]))
    }
}

function removeSourcesetFilterFromCache(sourceset) {
    let cached = safeLocalStorage.getItem('inactive-filters')
    if (cached) {
        let parsed = JSON.parse(cached)
        safeLocalStorage.setItem('inactive-filters', JSON.stringify(parsed.filter(p => p !== sourceset)))
    }
}

function refreshSourcesetsCache() {
    safeLocalStorage.setItem('inactive-filters', JSON.stringify(filteringContext.restrictedDependencies.filter(p => -1 === filteringContext.activeFilters.indexOf(p))))
}


function togglePlatformDependent(e, container) {
    let target = e.target
    if (target.tagName !== 'BUTTON') return;
    let index = target.getAttribute('data-toggle')

    for (let child of container.children) {
        if (child.hasAttribute('data-toggle-list')) {
            for (let bm of child.children) {
                if (bm === target) {
                    bm.setAttribute('data-active', "")
                } else if (bm !== target) {
                    bm.removeAttribute('data-active')
                }
            }
        } else if (child.getAttribute('data-togglable') === index) {
            child.setAttribute('data-active', "")
        } else {
            child.removeAttribute('data-active')
        }
    }
}

function refreshFiltering() {
    let sourcesetList = filteringContext.activeFilters
    document.querySelectorAll("[data-filterable-set]")
        .forEach(
            elem => {
                let platformList = elem.getAttribute("data-filterable-set").split(',').filter(v => -1 !== sourcesetList.indexOf(v))
                elem.setAttribute("data-filterable-current", platformList.join(','))
            }
        )
    refreshFilterButtons()
    refreshPlatformTabs()
    refreshNoContentNotification()
    refreshPlaygroundSamples()
}

function refreshPlaygroundSamples() {
    document.querySelectorAll('code.runnablesample').forEach(node => {
        const playground = node.KotlinPlayground;
        /* Some samples may be hidden by filter, they have 0px height  for visible code area
         * after rendering. Call this method for re-calculate code area height */
        playground && playground.view.codemirror.refresh();
    });
}

function refreshNoContentNotification() {
    const element = document.getElementsByClassName("main-content")[0]
    const filteredMessage = document.querySelector(".filtered-message")

    if(filteringContext.activeFilters.length === 0){
        element.style.display = "none";

        if (!filteredMessage) {
            const appended = document.createElement("div")
            appended.className = "filtered-message"
            appended.innerText = "All documentation is filtered, please adjust your source set filters in top-right corner of the screen"
            sourcesetNotification = appended
            element.parentNode.prepend(appended)
        }
    } else {
        if(sourcesetNotification) sourcesetNotification.remove()
        element.style.display = "block"
    }
}

function refreshPlatformTabs() {
    document.querySelectorAll(".platform-hinted > .platform-bookmarks-row").forEach(
        p => {
            let active = false;
            let firstAvailable = null
            p.childNodes.forEach(
                element => {
                    if (element.getAttribute("data-filterable-current") !== '') {
                        if (firstAvailable === null) {
                            firstAvailable = element
                        }
                        if (element.hasAttribute("data-active")) {
                            active = true;
                        }
                    }
                }
            )
            if (active === false && firstAvailable) {
                firstAvailable.click()
            }
        }
    )
}

function refreshFilterButtons() {
    document.querySelectorAll("#filter-section > button")
        .forEach(f => {
            if (filteringContext.activeFilters.indexOf(f.getAttribute("data-filter")) !== -1) {
                f.setAttribute("data-active", "")
            } else {
                f.removeAttribute("data-active")
            }
        })
    document.querySelectorAll("#filter-section .checkbox--input")
        .forEach(f => {
            f.checked = filteringContext.activeFilters.indexOf(f.getAttribute("data-filter")) !== -1;
        })
}
