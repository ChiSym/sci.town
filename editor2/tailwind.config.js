module.exports = {
    content: [
        "./public/editor/js/*.js",
        "./src/**/*",
        "../studio/src/**/*",
        "./public/index.html",
        "./public/studio.html"],
    theme: {
        extend: {
            colors: {
                "variableName": "var(--code-variableName)",
                "operator": "var(--code-operator)",
                "bool": "var(--code-bool)",
                "null": "var(--code-null)",
                "keyword": "var(--code-keyword)",
                "docString": "var(--code-docString)",
                "comment": "var(--code-comment)",
                "number": "var(--code-number)",
                "string": "var(--code-string)",
                "regexp": "var(--code-regexp)",
                "brackets": "var(--code-brackets)"
            }
        }
    },
    plugins: [
        require('@tailwindcss/typography'),
        require('@tailwindcss/forms'),
        require('@tailwindcss/line-clamp')
    ]
}