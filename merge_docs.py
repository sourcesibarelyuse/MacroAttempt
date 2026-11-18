print("Merging Architecture and Product Spec...")

with open("Architecture.md", "r", errors="ignore") as f:
    arch = f.read()

with open("Product_Spec.md", "r", errors="ignore") as f:
    spec = f.read()

# Add a transition
transition = "\n\n---\n\n# PART 2: PRODUCT SPECIFICATION & APP DESIGN\n\n"

with open("Final_Master_Plan.md", "w", encoding="utf-8") as f:
    f.write(arch + transition + spec)

print("✅ Successfully created Final_Master_Plan.md")