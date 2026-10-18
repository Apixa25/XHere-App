module.exports = {
  ci: {
    collect: {
      staticDistDir: "./dist",
      numberOfRuns: 1,
      url: [
        "http://localhost/",
        "http://localhost/d/JmtwnHist01a/",
        "http://localhost/privacy/",
        "http://localhost/terms/",
        "http://localhost/guidelines/",
        "http://localhost/delete-account/",
        "http://localhost/support/",
      ],
      settings: {
        chromeFlags: "--no-sandbox --disable-dev-shm-usage",
      },
    },
    assert: {
      assertions: {
        "categories:performance": ["error", { minScore: 0.9 }],
        "categories:accessibility": ["error", { minScore: 0.9 }],
        "categories:best-practices": ["error", { minScore: 0.9 }],
        "categories:seo": ["error", { minScore: 0.9 }],
      },
    },
    upload: {
      target: "filesystem",
      outputDir: "./.lighthouseci",
    },
  },
};
