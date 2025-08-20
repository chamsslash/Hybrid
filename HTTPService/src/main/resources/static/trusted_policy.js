
const policy = trustedTypes.createPolicy('default', {
    createHTML: (input) => DOMPurify.sanitize(input)
});
