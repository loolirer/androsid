**Issues and Task Tracking**: Before opening any Pull Request, discuss the design and scope in a GitHub issue first.
**Feature Branches**: Create focused branches off `main` targeting the specific task or feature.
**Atomic Changes**: Keep Pull Requests focused and minimal. Avoid combining large refactoring or linting diffs with functional feature implementations.

**REP-103**: Standard units of measure and coordinate system conventions must be respected (e.g., Forward-Left-Up body frames and SI units).

**Package Filesystem Layout**: Packages must conform to standard ROS 2 [filesystem layouts](https://docs.ros.org/en/foxy/The-ROS2-Project/Contributing/Developer-Guide.html#filesystem-layout).

All packages enforce the ROS 2 linter suite via `ament_lint_common` and `ament_lint_auto`.

Always run tests locally after making changes and before proposing them in a pull request:

```bash
colcon test --packages-select package_name
colcon test-result --verbose
```

Any contribution that you make to this repository will
be under the Apache 2 License, as dictated by that
[license](http://www.apache.org/licenses/LICENSE-2.0.html):

~~~
5. Submission of Contributions. Unless You explicitly state otherwise,
   any Contribution intentionally submitted for inclusion in the Work
   by You to the Licensor shall be under the terms and conditions of
   this License, without any additional terms or conditions.
   Notwithstanding the above, nothing herein shall supersede or modify
   the terms of any separate license agreement you may have executed
   with Licensor regarding such Contributions.
~~~

Contributors must sign-off each commit by adding a `Signed-off-by: ...`
line to commit messages to certify that they have the right to submit
the code they are contributing to the project according to the
[Developer Certificate of Origin (DCO)](https://developercertificate.org/).